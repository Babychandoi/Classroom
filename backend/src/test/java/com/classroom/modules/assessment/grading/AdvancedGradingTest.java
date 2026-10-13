package com.classroom.modules.assessment.grading;

import com.classroom.modules.assessment.doc.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class AdvancedGradingTest {
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "(x+1)^2|x^2+2*x+1|true", "x*y+y*x|2*x*y|true", "x/2|0.5*x|true", "(x*x-1)/(x-1)|x+1|false",
        "x/x|1|false", "x+1|x-1|false", "-x^2|-(x*x)|true", "x^2+y^2|(x+y)^2|false",
        "Runtime.getRuntime()|1|false", "alert(1)|1|false", "x^999|x|false", "1/0|1/0|false", "x[0]|x|false", "x;1|1|false"
    })void exactExpressionsAndDomains(String answer,String key,boolean same){assertEquals(same,SafeExpression.equivalent(answer,key));}
    @Test void complexityLimits(){assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),()->assertFalse(SafeExpression.equivalent("((((((((99999999999999999999^8)^8)^8)^8)^8)^8)^8)^8)","1")));}
    @Test void arithmeticVariables(){assertEquals(12,SafeExpression.evaluate("u*t",Map.of("u",3.0,"t",4.0)));assertThrows(IllegalArgumentException.class,()->SafeExpression.evaluate("u/0",Map.of("u",3.0)));}
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "2H2+O2=2H2O|4H2+2O2=4H2O|true", "O2+2H2=2H2O|2H2+O2=2H2O|true",
        "H2+O2=H2O|2H2+O2=2H2O|false", "Ca(OH)2+2HCl=CaCl2+2H2O|2Ca(OH)2+4HCl=2CaCl2+4H2O|true",
        "2H2O=2H2+O2|2H2+O2=2H2O|false", "2H2+O2=2H2O|2H2+O2=H2O2|false"
    })void chemicalBalanceAndProportionalCoefficients(String answer,String key,boolean same){assertEquals(same,ChemicalEquation.equivalent(answer,key));}
    static Stream<Arguments> gradingCases(){return Stream.of(
        Arguments.of("Q14","{\"regions\":[{\"id\":\"r1\",\"x\":0.1,\"y\":0.1,\"width\":0.3,\"height\":0.3}]}","{\"regionIds\":[\"r1\"]}","{\"regionId\":\"r1\"}","1"),
        Arguments.of("Q14","{\"regions\":[{\"id\":\"r1\",\"x\":0.1,\"y\":0.1,\"width\":0.3,\"height\":0.3}]}","{\"regionIds\":[\"r1\"]}","{\"x\":0.2,\"y\":0.2}","1"),
        Arguments.of("Q14","{}","{\"regionIds\":[\"r1\"]}","{\"regionId\":\"r2\"}","0"),
        Arguments.of("Q15","{}","{\"labels\":{\"t1\":\"l1\",\"t2\":\"l2\"}}","{\"labels\":{\"t1\":\"l1\",\"t2\":\"l1\"}}","0.5"),
        Arguments.of("Q16","{}","{\"categories\":{\"c1\":\"g1\",\"c2\":\"g2\"}}","{\"categories\":{\"c1\":\"g1\"}}","0.5"),
        Arguments.of("Q17","{}","{\"cells\":{\"c1\":[\"Hà Nội\",\"HN\"]}}","{\"cells\":{\"c1\":\"Ha Noi\"}}","0"),
        Arguments.of("Q17","{}","{\"cells\":{\"c1\":[\"Hà Nội\",\"HN\"]}}","{\"cells\":{\"c1\":\"hà   nội\"}}","1"),
        Arguments.of("Q18","{}","{\"values\":{\"p1\":{\"value\":1.25,\"tolerance\":0.01}}}","{\"values\":{\"p1\":\"1,255\"}}","1"),
        Arguments.of("Q18","{}","{\"values\":{\"p1\":1.25},\"tolerance\":0.01}","{\"values\":{\"p1\":\"1.3\"}}","0"),
        Arguments.of("Q19","{}","{\"accepted\":[\"(x+1)^2\"]}","{\"text\":\"x^2+2*x+1\"}","1"),
        Arguments.of("Q19","{\"chemical\":true}","{\"accepted\":[\"2H2+O2=2H2O\"]}","{\"text\":\"4H2+2O2=4H2O\"}","1"),
        Arguments.of("Q20","{\"steps\":[{\"id\":\"s1\",\"points\":1},{\"id\":\"s2\",\"points\":3}]}","{\"steps\":{\"s1\":[\"2\"],\"s2\":[\"4\"]}}","{\"steps\":{\"s1\":\"2\",\"s2\":\"3\"}}","0.25"),
        Arguments.of("Q27","{\"controls\":[{\"id\":\"a\",\"min\":1,\"max\":10}],\"formula\":\"a*2\"}","{\"values\":{\"result\":6},\"tolerance\":0.1}","{\"controls\":{\"a\":3},\"values\":{\"result\":999}}","1")
    );}
    @ParameterizedTest @MethodSource("gradingCases") void independentAdvancedGraders(String type,String interaction,String key,String response,String expected){
        ItemNode item=item(type,interaction,key);GradeOutcome outcome=GradingEngine.grade(item,DocumentCodec.readTree(response));assertEquals(0,new BigDecimal(expected).compareTo(outcome.fraction()));assertNotEquals("PENDING_MANUAL",outcome.status());
    }
    @Test void hybridPreservesSuggestionUntilTeacherGrades(){ItemNode item=item("Q18","{}","{\"values\":{\"p1\":3}}");item=new ItemNode(item.id(),item.type(),item.prompt(),item.promptBlocks(),item.points(),new GradingSpec("HYBRID",true,null,null,null),null,null,null,null,item.interaction(),item.answerKey());GradeOutcome grade=GradingEngine.grade(item,DocumentCodec.readTree("{\"values\":{\"p1\":3}}"));assertEquals("PENDING_MANUAL",grade.status());assertEquals(0,BigDecimal.ONE.compareTo(grade.points()));}
    @Test void passiveEvidenceIsNeverAutoGraded(){for(String type:List.of("Q23","Q24","Q25","Q28"))assertTrue(GradingEngine.isManualType(type));}
    private static ItemNode item(String type,String x,String key){return new ItemNode("q1",type,"Câu hỏi",List.of(),BigDecimal.ONE,new GradingSpec("AUTO",true,null,null,new Normalization(true,true,false)),null,null,null,List.of(),DocumentCodec.readTree(x),DocumentCodec.readTree(key));}
}
