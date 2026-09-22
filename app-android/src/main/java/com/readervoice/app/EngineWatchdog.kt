package com.readervoice.app

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * MOBILE-005 / M3：**引擎看门狗**（用户冻结的状态机）。
 *
 * 为什么不能只靠 `withTimeout()`：一旦进入 JNI，Kotlin 的协程取消只取消"外层等待"，
 * native 线程可能仍卡着。这里做两层：
 *
 * ```
 * RUNNING
 *   ↓ 超过 deadline
 * CANCEL_REQUESTED   → engine.cancelGeneration()（置 native 状态，AR 循环下一 token 边界退出）
 *   ↓ native 在 grace 窗口内返回
 * CANCELLED / TIMEOUT → 本段判定为 NO_OUTPUT（fail-closed），runner 继续下一段
 *   ↓ 超过 grace 仍不返回
 * ENGINE_STUCK       → **暂停后续模型调用**（不再往同一个已不可信的引擎上堆请求）
 * ```
 *
 * 关键点：native 调用跑在**独立线程**上（`future.get(timeout)`），因此即使 JNI 内部卡死，
 * 调用线程也能按时返回并继续整章；被放弃的那次 native 调用最多泄漏一个线程，
 * 但**绝不会**演变成"多个挂住的 generation 并发堆积"（ENGINE_STUCK 之后不再发起新调用）。
 */
class EngineWatchdog(
    private val engine: QwenEngine,
    /** 单次生成的 wall-clock 上限（native 侧同样按此 deadline 检查）。 */
    private val timeoutMs: Long = 30_000,
    /** 请求取消后，等待 native 真正返回的宽限期。 */
    private val graceMs: Long = 15_000,
) {

    enum class State { RUNNING, CANCEL_REQUESTED, CANCELLED, TIMEOUT, STUCK }

    @Volatile var state: State = State.RUNNING
        private set

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "director-engine").apply { isDaemon = true }
    }

    var calls = 0; private set
    var timeouts = 0; private set
    var canceled = 0; private set
    var stuckEvents = 0; private set

    /** 当前 epoch：跳章/超时后自增；旧 epoch 的结果一律丢弃。 */
    private val epoch = AtomicLong(0)

    fun bumpEpoch() {
        epoch.incrementAndGet()
    }

    fun currentEpoch(): Long = epoch.get()

    /** 请求取消当前生成（协作式）。 */
    fun requestCancel() {
        state = State.CANCEL_REQUESTED
        bumpEpoch()
        runCatching { engine.cancelGeneration() }
    }

    val isStuck: Boolean get() = state == State.STUCK

    /**
     * 执行一次生成。
     * @return 模型输出；null = NO_OUTPUT（超时/取消/引擎已 stuck）—— 上层按 fail-closed 处理
     */
    fun generate(prompt: String, maxTokens: Int, epochAtStart: Long = currentEpoch()): String? {
        if (state == State.STUCK) return null          // ★ 暂停后续调用，不再堆请求
        state = State.RUNNING
        calls++
        val task = Callable {
            when (val o = engine.generateDeadline(prompt, maxTokens, timeoutMs)) {
                is QwenEngine.GenOutcome.Ok -> o.text
                QwenEngine.GenOutcome.Timeout -> "__TIMEOUT__"
                QwenEngine.GenOutcome.Canceled -> "__CANCELED__"
                QwenEngine.GenOutcome.NotLoaded -> null
            }
        }
        val future = executor.submit(task)
        return try {
            val r = future.get(timeoutMs + graceMs, TimeUnit.MILLISECONDS)
            when (r) {
                "__TIMEOUT__" -> { state = State.TIMEOUT; timeouts++; null }
                "__CANCELED__" -> { state = State.CANCELLED; canceled++; null }
                else -> {
                    if (epoch.get() != epochAtStart) { state = State.CANCELLED; canceled++; null }
                    else r
                }
            }
        } catch (t: TimeoutException) {
            // native 没在 deadline+grace 内返回 ⇒ 请求取消，再给一次宽限
            requestCancel()
            try {
                val r = future.get(graceMs, TimeUnit.MILLISECONDS)
                state = State.TIMEOUT
                timeouts++
                if (r == "__CANCELED__") null else null
            } catch (t2: TimeoutException) {
                state = State.STUCK
                stuckEvents++
                future.cancel(true)                    // 尽力而为；不依赖它生效
                null
            } catch (t2: ExecutionException) {
                state = State.TIMEOUT; timeouts++; null
            } catch (t2: InterruptedException) {
                Thread.currentThread().interrupt(); state = State.STUCK; stuckEvents++; null
            }
        } catch (t: ExecutionException) {
            state = State.TIMEOUT; timeouts++; null
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt(); state = State.STUCK; stuckEvents++; null
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    fun summary(): String =
        "calls=$calls timeouts=$timeouts canceled=$canceled stuck=$stuckEvents state=$state"
}
