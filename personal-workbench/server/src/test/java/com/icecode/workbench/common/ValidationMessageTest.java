package com.icecode.workbench.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

/**
 * 字段校验的文案必须能指导操作。
 *
 * 背景（2026-09-11 实测）：`GlobalExceptionHandler` 会把 `MethodArgumentNotValidException`
 * 的字段提示原样透传给前端 toast。但绝大多数约束注解没写 `message`，于是用户看到的是
 * Hibernate Validator 的英文默认文案：
 *   @NotBlank      -> "must not be blank"
 *   @Size(max=72)  -> "size must be between 0 and 72"（还会让人误以为下限是 0）
 *   @Pattern(...)  -> must match "^$|([01]\d|2[0-3]):[0-5]\d"（直接把正则甩给用户）
 *
 * 这里做成结构性约束而不是零散断言：以后新增请求 DTO 时忘了写 message，这条用例会直接拦住。
 */
class ValidationMessageTest {

    private static final String[] CONSTRAINT_PACKAGES = {
            "javax.validation.constraints.",
            "org.hibernate.validator.constraints."
    };

    @Test
    void everyFieldConstraintCarriesAnActionableChineseMessage() throws Exception {
        List<String> offenders = new ArrayList<String>();

        for (Class<?> type : requestClasses()) {
            for (Field field : type.getDeclaredFields()) {
                for (Annotation annotation : field.getAnnotations()) {
                    if (!isConstraint(annotation)) continue;
                    String message = messageOf(annotation);
                    String where = type.getSimpleName() + "." + field.getName()
                            + " @" + annotation.annotationType().getSimpleName();
                    if (message == null || message.trim().isEmpty()) {
                        offenders.add(where + " -> message 为空");
                    } else if (message.startsWith("{")) {
                        // 形如 {javax.validation.constraints.NotBlank.message}：没有自定义 message
                        offenders.add(where + " -> 仍是默认文案占位符 " + message);
                    } else if (!containsCjk(message)) {
                        offenders.add(where + " -> 文案不是中文：" + message);
                    }
                }
            }
        }

        assertThat(offenders)
                .as("这些约束会把英文机器文案透给用户，请补上中文 message（要能指导操作，格式类字段写清期望格式）")
                .isEmpty();
    }

    /** 覆盖扫描：只要类名以 Request 结尾就当请求 DTO 检查。 */
    private List<Class<?>> requestClasses() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*Request")));

        List<Class<?>> classes = new ArrayList<Class<?>>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.icecode.workbench")) {
            classes.add(Class.forName(definition.getBeanClassName()));
        }
        assertThat(classes).as("没有扫描到任何请求 DTO，包名或过滤规则可能失效了").isNotEmpty();
        return classes;
    }

    private boolean isConstraint(Annotation annotation) {
        String name = annotation.annotationType().getName();
        for (String prefix : CONSTRAINT_PACKAGES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private String messageOf(Annotation annotation) throws ReflectiveOperationException {
        Method method = annotation.annotationType().getMethod("message");
        Object value = method.invoke(annotation);
        return value == null ? null : value.toString();
    }

    private boolean containsCjk(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) >= 0x4E00 && value.charAt(i) <= 0x9FFF) return true;
        }
        return false;
    }
}
