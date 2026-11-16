package com.panel.knowledge.application;

/** 图谱规模：概念数 + 关系数，用来看某个库抽了多少。 */
public record KbGraphStats(int concepts, long edges) {
}
