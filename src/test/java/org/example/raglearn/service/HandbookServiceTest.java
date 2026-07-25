package org.example.raglearn.service;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Slf4j
class HandbookServiceTest {
    @Autowired
    private HandbookService handbookService;

    @Test
    void getAnswer() {
        var str = "机械创新设计大赛二等奖团体内第三名有多少分？";
        var answer = handbookService.getAnswer(str);
        log.info(answer);
    }
}
