package server;

import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

/**
 * Netty 配置：增加工作线程数以处理高并发 WebSocket 连接
 * 
 * 默认情况下，Reactor Netty 使用 CPU 核心数的工作线程。
 * 对于高并发 I/O 密集型任务（如 WebSocket），需要更多线程。
 * 
 * CPU 使用率 60% 但吞吐量受限，通常是因为：
 * 1. 线程数不足，导致消息在队列中等待
 * 2. 网络 I/O 延迟（往返时间 RTT）
 * 3. 背压（backpressure）导致消息积压
 */
@Configuration
public class NettyConfig {

    /**
     * 自定义 Netty 工作线程数
     * 根据你的 EC2 实例配置调整（例如：4 核 CPU -> 16-32 线程）
     */
    @Bean
    public WebServerFactoryCustomizer<NettyReactiveWebServerFactory> nettyCustomizer() {
        return factory -> {
            // 设置工作线程数：建议为 CPU 核心数的 4-8 倍
            // 例如：4 核 -> 16-32 线程，8 核 -> 32-64 线程
            // 可以通过系统属性覆盖：-Dnetty.worker.threads=32
            int workerThreads = Integer.getInteger("netty.worker.threads", 
                Math.max(16, Runtime.getRuntime().availableProcessors() * 4));
            
            factory.addServerCustomizers(httpServer -> {
                EventLoopGroup eventLoopGroup = new NioEventLoopGroup(workerThreads);
                return httpServer.runOn(eventLoopGroup);
            });
        };
    }
}
