// SYNTHETIC SECRET FIXTURE — 全部为 FAKE 值，不包含任何真实凭据。
// 用途: secret_scan.js --self-test 的回归样本（覆盖首轮漏检的每种形态）。
// 注意: 不得把真实 key 写入本文件。

var DualKeyManager = (function () {
  var defaultConfig = {
    endpoint: 'https://open.bigmodel.cn/api/paas/v4/chat/completions',
    // 单引号 + 32hex.suffix 供应商格式（本项目首轮漏检形态）
    key: 'a1b2c3d4e5f60718293a4b5c6d7e8f901.fakeSuffix1234'
  };
  return { defaultConfig: defaultConfig };
})();

var token = "sk-fake-double-quoted-token-abcdefghijklmnopqrstuvwxyz123";

// API_KEY = 'FAKE_API_KEY_abcdefghijklmnopqrstuvwxyz'
// api_key: "fake_api_key_in_double_quotes_123456789"

var headers = {
  Authorization: 'Bearer fake.authorization.token.payload'
};

var urlWithCreds = "https://user:password123@example.com/api/v1/upload";

var hexOnly = "0123456789abcdef0123456789abcdef";

var privateKeyBlock = "-----BEGIN RSA PRIVATE KEY-----\nFAKEKEYDATA\n-----END RSA PRIVATE KEY-----";
