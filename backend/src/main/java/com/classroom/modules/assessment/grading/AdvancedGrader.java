package com.classroom.modules.assessment.grading;

import com.classroom.modules.assessment.doc.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;

/** P1/P2 tasks share bounded keyed/geometry/numeric graders, independently of manual/hybrid policy. */
public final class AdvancedGrader implements ItemGrader {
    @Override public Evaluation evaluate(ItemNode item,JsonNode response){
        if(response==null || response.isNull() || !response.isObject() || response.isEmpty())return Evaluation.blankAnswer();
        JsonNode key=item.answerKey();Map<String,Boolean> sub=new LinkedHashMap<>();
        switch(item.type()) {
            case "Q14" -> {
                String region=response.path("regionId").asText();boolean correct=false;
                if(!region.isBlank()) {for(JsonNode id:key.path("regionIds"))if(id.asText().equals(region))correct=true;}
                else if(response.path("x").isNumber() && response.path("y").isNumber()) {
                    double x=response.path("x").asDouble(),y=response.path("y").asDouble();
                    for(JsonNode r:item.interaction().path("regions"))if(contains(key.path("regionIds"),r.path("id").asText()) && x>=r.path("x").asDouble() && y>=r.path("y").asDouble() && x<=r.path("x").asDouble()+r.path("width").asDouble() && y<=r.path("y").asDouble()+r.path("height").asDouble())correct=true;
                }
                sub.put("region",correct);
            }
            case "Q15" -> mapped(sub,key,response,"labels",false,item);
            case "Q16" -> mapped(sub,key,response,"categories",false,item);
            case "Q17" -> mapped(sub,key,response,"cells",true,item);
            case "Q27" -> {
                Map<String,Double> controls=new HashMap<>();boolean valid=true;
                for(JsonNode control:item.interaction().path("controls")) {
                    JsonNode given=response.path("controls").path(control.path("id").asText());double value=given.asDouble(Double.NaN);
                    if(!given.isNumber() || !Double.isFinite(value) || value<control.path("min").asDouble() || value>control.path("max").asDouble())valid=false;
                    controls.put(control.path("id").asText(),value);
                }
                double value=Double.NaN;try{if(valid && !controls.isEmpty())value=SafeExpression.evaluate(item.interaction().path("formula").asText(),controls);}catch(IllegalArgumentException ignored){valid=false;}
                JsonNode k=key.path("values").path("result");double expected=k.isObject()?k.path("value").asDouble(Double.NaN):k.asDouble(Double.NaN),tolerance=k.isObject()?k.path("tolerance").asDouble():key.path("tolerance").asDouble();
                sub.put("result",valid && Double.isFinite(value) && Double.isFinite(expected) && tolerance>=0 && Math.abs(value-expected)<=tolerance+1e-10);
            }
            case "Q18" -> {
                String field="values";JsonNode values=key.path(field);values.fieldNames().forEachRemaining(id->{JsonNode a=response.path(field).path(id),k=values.path(id);double expected=k.isObject()?k.path("value").asDouble(Double.NaN):k.asDouble(Double.NaN),tolerance=k.isObject()?k.path("tolerance").asDouble(0):key.path("tolerance").asDouble(0);
                    BigDecimal n=NumericParser.parse(a.asText(),List.of()).orElse(null);sub.put(id,n!=null && Double.isFinite(expected) && Math.abs(n.doubleValue()-expected)<=tolerance+1e-10);});
            }
            case "Q19" -> {String text=response.path("text").asText();if(text.isBlank())return Evaluation.blankAnswer();boolean correct=false;
                for(JsonNode accepted:key.path("accepted")) if(item.interaction().path("chemical").asBoolean()?ChemicalEquation.equivalent(text,accepted.asText()):SafeExpression.equivalent(text,accepted.asText()))correct=true;sub.put("expression",correct);}
            case "Q20" -> {key.path("steps").fieldNames().forEachRemaining(id->{String value=response.path("steps").path(id).asText();boolean correct=false;for(JsonNode expected:key.path("steps").path(id))if(TextNormalizer.normalize(value,item.grading().normalization()).equals(TextNormalizer.normalize(expected.asText(),item.grading().normalization())))correct=true;sub.put(id,correct);});}
            default -> {return Evaluation.blankAnswer();}
        }
        if(sub.isEmpty())return Evaluation.blankAnswer();
        BigDecimal fraction;
        if("Q20".equals(item.type()) && item.grading().partialCredit()) {
            BigDecimal all=BigDecimal.ZERO,earned=BigDecimal.ZERO;for(JsonNode step:item.interaction().path("steps")){BigDecimal p=step.path("points").decimalValue();all=all.add(p);if(Boolean.TRUE.equals(sub.get(step.path("id").asText())))earned=earned.add(p);}fraction=all.signum()==0?BigDecimal.ZERO:earned.divide(all,ScoreMath.MC);
        } else fraction=item.grading().partialCredit()?ScoreMath.ratio((int)sub.values().stream().filter(Boolean.TRUE::equals).count(),sub.size()):(sub.values().stream().allMatch(Boolean.TRUE::equals)?BigDecimal.ONE:BigDecimal.ZERO);
        return new Evaluation(false,fraction,sub);
    }
    private static void mapped(Map<String,Boolean> sub,JsonNode key,JsonNode response,String field,boolean text,ItemNode item){JsonNode keys=key.path(field);keys.fieldNames().forEachRemaining(id->{String value=response.path(field).path(id).asText();JsonNode expected=keys.path(id);boolean correct=false;if(expected.isArray()){for(JsonNode e:expected)if(equal(value,e.asText(),text,item))correct=true;}else correct=equal(value,expected.asText(),text,item);sub.put(id,correct);});}
    private static boolean equal(String a,String b,boolean text,ItemNode item){return !a.isBlank() && (text?TextNormalizer.normalize(a,item.grading().normalization()).equals(TextNormalizer.normalize(b,item.grading().normalization())):a.equals(b));}
    private static boolean contains(JsonNode ids,String id){for(JsonNode n:ids)if(n.asText().equals(id))return true;return false;}
    @Override public JsonNode correctResponse(ItemNode item){return item.answerKey();}
}
