package io.github.greytaiwolf.botplayer.client.ai;

/**
 * 只在物理客户端内复制当前请求需要的本地 DeepSeek 密钥。
 *
 * <p>返回数组的所有权交给调用方；调用方必须尽快清零。该接口不暴露 profile 名、Key 指纹或
 * 服务器侧身份，因此不能被网络 payload 或 common server 代码当作凭据传输边界。</p>
 */
@FunctionalInterface
public interface DeepSeekCredentialSupplier {
    char[] copySecret() throws DeepSeekProviderException;
}
