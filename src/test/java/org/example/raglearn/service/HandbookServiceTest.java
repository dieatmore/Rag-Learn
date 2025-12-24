package org.example.raglearn.service;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Slf4j
class HandbookServiceTest {
    @Autowired
    private HandbookService handbookService;

    @Test
    void getAnswer() {
        // var str = "我离职要交接什么？";
        // var str = "公司的年假有几天？";
        // var str = "可以请几天病假呢？";
        // var str = "我有张发票已经搁置40天了，还能报销么？";
//        var str = "请问，有多长时间的午休时间？";
        var str = "我的工资是多少？";


        var answer = handbookService.getAnswer(str);
        log.debug(answer);
    }
}