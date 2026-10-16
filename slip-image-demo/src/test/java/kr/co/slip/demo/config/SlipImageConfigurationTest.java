package kr.co.slip.demo.config;

import kr.co.slip.api.SlipImageProcessor;
import kr.co.slip.api.ManagedSlipImageProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class SlipImageConfigurationTest {
    @Test void hostCanRegisterProcessorWithoutScanningImplementationPackages() {
        java.util.concurrent.atomic.AtomicReference<ManagedSlipImageProcessor> processor = new java.util.concurrent.atomic.AtomicReference<>();
        new ApplicationContextRunner().withUserConfiguration(SlipImageConfiguration.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(SlipImageProcessor.class).size());
            assertNotNull(context.getBean(SlipImageProcessor.class));
            processor.set(context.getBean(ManagedSlipImageProcessor.class));
        });
        assertTrue(processor.get().status().closed());
    }
}
