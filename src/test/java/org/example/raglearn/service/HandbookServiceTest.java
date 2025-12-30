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
//        var str = "蓝桥杯二等奖可以得多少分？";
//        var str = "全国大学生工业设计大赛属于哪个等级";
//        var str = "挑战杯竞赛能得多少分";
//        var str = "论文发表日期截止到什么时候";
//        var str = "中科院 3 分区期刊属于什么级别论文";
//        var str = "集体竞赛项目主力成员认定规则";
//        var str = "蓝桥杯一等奖可以得多少分啊？";
//        var str = "机械创新设计大赛第二名有多少分？";
//        var str = "全国大学生数学竞赛第五名可以得多少分？";

//        var str = "软件创新大赛第五名可以得多少分？";
//        var str = "机械创新设计大赛第二名有多少分？";
//        var str = "机械创新设计大赛团体内第三名有多少分？";
//        var str = "机械创新设计大赛第三名团体内第三名有多少分？";
//        var str = "机械创新设计大赛第三名团体内第二名有多少分？";
//        var str = "机械创新设计大赛二等奖团体内第三名有多少分？";
//        var str = "机械创新设计大赛二等奖团体内第二名有多少分？";
//        var str = "全国大学生数学竞赛第一名有多少分?";
//        var str = "全国大学生数学竞赛一等奖有多少分?";
//        var str = "机械创新设计大赛是个人项目还是集体项目？";
        var str = "我得了一个机械创新设计大赛二等奖和全国大学生数学竞赛一等奖，有多少分？";
        var answer = handbookService.getAnswer(str);


        log.debug(answer);
    }
}
