package kr.co.slip.demo.config;

import kr.co.slip.api.SlipImageProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class SlipImageConfigurationTest {
    @Test void hostCanRegisterProcessorWithoutScanningImplementationPackages() {
        new ApplicationContextRunner().withUserConfiguration(SlipImageConfiguration.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(SlipImageProcessor.class).size());
            assertNotNull(context.getBean(SlipImageProcessor.class));
        });
    }
}
