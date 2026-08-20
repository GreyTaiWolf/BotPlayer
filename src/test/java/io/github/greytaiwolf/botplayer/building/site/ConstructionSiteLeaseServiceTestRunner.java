package io.github.greytaiwolf.botplayer.building.site;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** 无 Minecraft classpath 时也可直接执行的 P5D-A6 纯模型测试入口。 */
public final class ConstructionSiteLeaseServiceTestRunner {
    private ConstructionSiteLeaseServiceTestRunner() {}

    public static void main(String[] args) throws Exception {
        Class<?>[] classes = {
            ConstructionSiteLeaseServiceTest.class
        };
        int passed = 0;
        for (Class<?> type : classes) {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object instance = constructor.newInstance();
            for (Method method : type.getDeclaredMethods()) {
                if (method.getAnnotation(Test.class) == null) {
                    continue;
                }
                method.setAccessible(true);
                try {
                    method.invoke(instance);
                    passed++;
                } catch (InvocationTargetException exception) {
                    throw new AssertionError(type.getSimpleName() + "." + method.getName(),
                            exception.getCause());
                }
            }
        }
        System.out.println("Passed " + passed + " construction site lease model tests");
    }
}
