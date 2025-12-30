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

//        var str = "软件创新大赛第五名可以得多少分？";
//        var str = "机械创新设计大赛第二名有多少分？";
//        var str = "机械创新设计大赛团体内第三名有多少分？";
//        var str = "机械创新设计大赛第三名团体内第三名有多少分？";
//        var str = "机械创新设计大赛第三名团体内第二名有多少分？";
        var str = "机械创新设计大赛二等奖团体内第三名有多少分？";
//        var str = "机械创新设计大赛二等奖团体内第二名有多少分？";
//        var str = "全国大学生数学竞赛第一名有多少分?";
//        var str = "全国大学生数学竞赛一等奖有多少分?";
//        var str = "机械创新设计大赛是个人项目还是集体项目？";
//        var str = "我得了一个机械创新设计大赛二等奖和全国大学生数学竞赛一等奖，有多少分？";
        var answer = handbookService.getAnswer(str);


        log.debug(answer);
    }
}
