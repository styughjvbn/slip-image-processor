package kr.co.slip.demo.config;

import kr.co.slip.api.ManagedSlipImageProcessor;
import kr.co.slip.api.ExecutionSettings;
import kr.co.slip.opencv.SlipProcessors;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Demo composition root. Host applications register this bean in their own configuration. */
@Configuration(proxyBeanMethods = false)
public class SlipImageConfiguration {
    @Bean(destroyMethod = "close")
    ManagedSlipImageProcessor slipImageProcessor(
            @Value("${slip.execution.workers:2}") int workers,
            @Value("${slip.execution.queue-capacity:4}") int queueCapacity,
            @Value("${slip.execution.opencv-threads:1}") int openCvThreads,
            @Value("${slip.execution.shutdown-seconds:30}") int shutdownSeconds) {
        return SlipProcessors.bounded(new ExecutionSettings(workers, queueCapacity,
                Duration.ofSeconds(shutdownSeconds)), openCvThreads);
    }
}
