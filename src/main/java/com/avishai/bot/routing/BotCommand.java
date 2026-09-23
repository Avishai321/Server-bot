package com.avishai.bot.routing;

import com.avishai.bot.handlers.HandlerCategory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BotCommand {
    String[] command();
    HandlerCategory category() default HandlerCategory.OTHER;
    String description() default "";
    String detailedHelp() default "No detailed documentation available";
    String[] arguments() default {};
    String[] examples() default {};
}
