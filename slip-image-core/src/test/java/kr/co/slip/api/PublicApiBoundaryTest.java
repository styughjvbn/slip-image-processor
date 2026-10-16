package kr.co.slip.api;

import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicApiBoundaryTest {
    @Test void publicContractsExposeOnlyJavaAndApiValues() {
        inspect(SlipImageProcessor.class);
        inspect(ProcessingOptions.class);
        inspect(ProcessingResult.class);
        inspect(ManagedSlipImageProcessor.class);
        inspect(ExecutionSettings.class);
        inspect(OutputMode.class);
        inspect(ProcessingRejectedException.class);
        inspect(ProcessingInterruptedException.class);
    }

    private static void inspect(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) if (Modifier.isPublic(method.getModifiers())) {
            allowed(method.getGenericReturnType());
            for (Type parameter : method.getGenericParameterTypes()) allowed(parameter);
        }
        for (Constructor<?> constructor : type.getConstructors())
            for (Type parameter : constructor.getGenericParameterTypes()) allowed(parameter);
        for (Class<?> nested : type.getDeclaredClasses()) inspect(nested);
    }

    private static void allowed(Type type) {
        if (type instanceof Class<?> value) {
            if (value.isArray()) { allowed(value.getComponentType()); return; }
            assertTrue(value.isPrimitive() || value.getPackageName().startsWith("java.")
                    || value.getPackageName().equals("kr.co.slip.api"), () -> "Implementation leaked: " + value);
        } else if (type instanceof ParameterizedType value) {
            allowed(value.getRawType());
            for (Type argument : value.getActualTypeArguments()) allowed(argument);
        } else fail("Unexpected API type: " + type);
    }
}
