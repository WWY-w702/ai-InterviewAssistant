package com.faceai.pdfreader;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("""
        需要外部 MySQL 与带 RediSearch 模块的 Redis 才能加载上下文；本机与当前 CI 都没有。
        而且 contextLoads 没有任何断言，即便通过也证明不了什么。
        待 Wave 7 接入 Testcontainers 后启用。

        注：@Disabled 必须放在类上。放在方法上时 SpringExtension 的
        TestInstancePostProcessor 会先于方法级 ExecutionCondition 执行，
        上下文照样被拉起，测试仍会因连不上库而报错。""")
class PdfReaderBackendApplicationTests {

    @Test
    void contextLoads() {
    }
}
