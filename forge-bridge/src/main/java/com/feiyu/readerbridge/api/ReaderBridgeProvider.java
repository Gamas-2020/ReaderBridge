package com.feiyu.readerbridge.api;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link ReaderBridgeApi} 的全局访问入口。
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * ReaderBridgeProvider.getIfPresent().ifPresent(api -> {
 *     api.addListener(state -> System.out.println(state.bookTitle()));
 * });
 * }</pre>
 *
 * <p>该类不持有任何内部实现类型的引用，只暴露 {@code api} 包的接口，
 * 因此第三方模组<b>只需要依赖本模组的 jar</b> 即可工作，不会被迫链接内部实现类。</p>
 */
public final class ReaderBridgeProvider {

    /** 当前注册的实现实例；由桥接模组自身在加载期安装。 */
    private static volatile ReaderBridgeApi instance;

    private ReaderBridgeProvider() {
    }

    /**
     * 获取桥接 API。
     *
     * @return 桥接 API 实例，永不为 {@code null}
     * @throws IllegalStateException 桥接模组尚未初始化时抛出
     */
    public static ReaderBridgeApi get() {
        ReaderBridgeApi api = instance;
        if (api == null) {
            throw new IllegalStateException(
                    "ReaderBridge 尚未初始化：请确认 readerbridge 模组已加载，且当前侧（客户端/服务端）支持桥接");
        }
        return api;
    }

    /**
     * 安全地探测桥接 API。
     *
     * @return 桥接已初始化时返回包含实例的 {@link Optional}，否则返回 {@link Optional#empty()}
     */
    public static Optional<ReaderBridgeApi> getIfPresent() {
        return Optional.ofNullable(instance);
    }

    /**
     * 安装桥接实现。
     *
     * <p><b>仅供 readerbridge 模组内部调用</b>，第三方模组不应调用本方法。它是 public 的
     * 唯一原因是实现类位于另一个包，无法使用包级可见性。重复安装会覆盖前一个实例。</p>
     *
     * @param api 实现实例
     */
    public static void install(ReaderBridgeApi api) {
        Objects.requireNonNull(api, "api");
        instance = api;
    }
}
