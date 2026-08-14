package io.github.greytaiwolf.botplayer.ai.transport;

/**
 * 由服务端固定并写入 owner-client 请求关联的狭窄用途。
 *
 * <p>这不是客户端授予的能力，也不是自由文本标签。物理客户端只会为自己明确支持的用途启动
 * 本地 Provider；未知、旧的 {@link #UNSPECIFIED_V1} 或以后新增但尚未本地允许的用途必须
 * 拒绝，而不能降级为通用聊天请求。
 */
public enum AiRequestPurpose {
    /** 兼容旧的纯基础设施测试/DTO；物理客户端绝不为它启动凭据请求。 */
    UNSPECIFIED_V1,
    /** P6-R1：owner 手动发起的、只读快照审阅，永不授权执行。 */
    REVIEW_ONLY_V1
}
