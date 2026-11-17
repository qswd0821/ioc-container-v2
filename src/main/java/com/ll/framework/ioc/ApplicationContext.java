package com.ll.framework.ioc;

import com.ll.framework.ioc.annotations.Component;
import com.ll.standard.util.Ut;
import org.reflections.Reflections;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class ApplicationContext {
    private final String basePackage;
    // 빈 이름 -> 빈 클래스 (스캔으로 모은 빈 생성 정보)
    private final Map<String, Class<?>> beanDefinitions = new HashMap<>();
    // 빈 이름 -> 싱글톤 인스턴스
    private final Map<String, Object> beans = new HashMap<>();
    // 생성 중인 빈 이름 (순환 의존 감지용)
    private final Set<String> beansInCreation = new LinkedHashSet<>();

    public ApplicationContext(String basePackage) {
        this.basePackage = basePackage;
    }

    public void init() {
        scanComponents();

        for (String beanName : beanDefinitions.keySet()) {
            genBean(beanName);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T genBean(String beanName) {
        Object bean = beans.get(beanName);

        if (bean == null) {
            bean = createBean(beanName);
        }

        return (T) bean;
    }

    private void scanComponents() {
        Reflections reflections = new Reflections(basePackage);

        // @Service, @Repository 처럼 @Component 가 붙은 애너테이션을 단 클래스도 함께 찾는다.
        for (Class<?> clazz : reflections.getTypesAnnotatedWith(Component.class)) {
            if (!isInstantiableClass(clazz)) continue;

            String beanName = Ut.str.lcfirst(clazz.getSimpleName());
            Class<?> existing = beanDefinitions.putIfAbsent(beanName, clazz);

            if (existing != null && existing != clazz) {
                throw new IllegalStateException(
                        "Duplicate bean name '%s': %s, %s".formatted(beanName, existing.getName(), clazz.getName())
                );
            }
        }
    }

    private boolean isInstantiableClass(Class<?> clazz) {
        return !clazz.isAnnotation()
                && !clazz.isInterface()
                && !clazz.isEnum()
                && !Modifier.isAbstract(clazz.getModifiers());
    }

    private Object createBean(String beanName) {
        Class<?> beanClass = beanDefinitions.get(beanName);

        if (beanClass == null) {
            throw new IllegalArgumentException("No bean named '%s'".formatted(beanName));
        }

        if (!beansInCreation.add(beanName)) {
            throw new IllegalStateException("Circular dependency: %s -> %s".formatted(beansInCreation, beanName));
        }

        try {
            Constructor<?> constructor = selectConstructor(beanClass);

            // 생성자 파라미터 타입으로 의존 빈을 알아내 먼저 만든다.
            Object[] args = Arrays.stream(constructor.getParameterTypes())
                    .map(paramType -> genBean(Ut.str.lcfirst(paramType.getSimpleName())))
                    .toArray();

            constructor.setAccessible(true);
            Object bean = constructor.newInstance(args);
            beans.put(beanName, bean);

            return bean;
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Failed to create bean '%s'".formatted(beanName), e);
        } finally {
            beansInCreation.remove(beanName);
        }
    }

    private Constructor<?> selectConstructor(Class<?> beanClass) {
        // 파라미터가 가장 많은 생성자를 사용한다. (@RequiredArgsConstructor 로 만든 생성자)
        return Arrays.stream(beanClass.getDeclaredConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow();
    }
}
