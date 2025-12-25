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
//         var str = "我离职要交接什么？";
//         var str = "公司的年假有几天？";
//         var str = "可以请几天病假呢？";
//         var str = "我有张发票已经搁置40天了，还能报销么？";
//         var str = "请问，有多长时间的午休时间？";
//         var str = "我的工资是多少？";
//        var str = "蓝桥杯二等奖可以得多少分？";
//        var str = "全国大学生工业设计大赛属于哪个等级";
//        var str = "挑战杯竞赛能得多少分";
//        var str = "论文发表日期截止到什么时候";
//        var str = "中科院 3 分区期刊属于什么级别论文";
        var str = "集体竞赛项目主力成员认定规则";
        var answer = handbookService.getAnswer(str);
        log.debug(answer);
    }
}