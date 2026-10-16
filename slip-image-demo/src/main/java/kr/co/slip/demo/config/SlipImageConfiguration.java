package kr.co.slip.demo.config;

import kr.co.slip.api.SlipImageProcessor;
import kr.co.slip.opencv.SlipProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Demo composition root. Host applications register this bean in their own configuration. */
@Configuration(proxyBeanMethods = false)
public class SlipImageConfiguration {
    @Bean
    SlipImageProcessor slipImageProcessor() { return new SlipProcessor(); }
}
