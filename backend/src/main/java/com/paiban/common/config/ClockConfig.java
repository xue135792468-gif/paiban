package com.paiban.common.config;

import com.paiban.substitution.service.LessonDeadline;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 服务端权威时钟（规格第 6 节）。学校时区固定 Asia/Shanghai：
 * 开课截止 = 实际日期 + 课节开始时间（Asia/Shanghai），判断一律以这个时钟为准，
 * 浏览器时区、页面停留时间与服务器默认时区都不是依据。
 *
 * <p>独立成 bean 是为了让集成测试能用固定时钟验证「截止前一瞬可确认、
 * 恰好开课与开课之后拒绝」的三分边界。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(LessonDeadline.SCHOOL_ZONE);
    }
}
