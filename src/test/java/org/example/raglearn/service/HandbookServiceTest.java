package org.example.raglearn.service;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;

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

    // 批量测试所有 RAGAS 测试用例（重点！一次性看全所有回答）
    @Test
    void testBatchAnswers() {
        // 定义所有 RAGAS 测试用例的问题列表
        List<String> testQuestions = Arrays.asList(
                // RAGAS 测试用例1
                "全国大学生机械创新设计大赛二等奖能加多少分？",
                // RAGAS 测试用例2
                "中国国际互联网+大学生创新创业大赛一等奖属于第几等级竞赛？能加多少分？",
                // RAGAS 测试用例3
                "蓝桥杯全国软件和信息技术专业人才大赛二等奖能加多少分？",
                // RAGAS 测试用例4
                "以东北林业大学为第一署名单位，独立作者发表的中科院2区期刊论文能加多少分？",
                // RAGAS 测试用例5
                "同时获得全国大学生数学竞赛一等奖和EI期刊论文（Ja检索），总计能加多少分？"
        );

        // 批量输出每个问题的实际回答
        log.debug("========== 批量测试所有问题的回答 ==========\n");
        for (String question : testQuestions) {
            String answer = handbookService.getAnswer(question);
            log.debug("问题：{}", question);
            log.debug("回答：{}", answer);
            log.debug("-----------------------------------------\n");
        }
    }
}
