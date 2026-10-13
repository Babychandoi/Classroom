package com.classroom.modules.assessment.doc;

import com.classroom.common.*;
import com.classroom.modules.assessment.grading.SafeExpression;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.*;

/** Per-attempt deterministic parameters: the immutable template and shuffle seed fully reproduce prompts and grading keys. */
public final class VariableDocument {
    private VariableDocument() {}
    public static AssessmentDocument instantiate(AssessmentDocument document,long seed) {
        ObjectNode tree=(ObjectNode)DocumentCodec.toTree(document);for(JsonNode section:tree.path("sections"))for(JsonNode node:section.path("nodes")){
            if("GROUP".equals(node.path("kind").asText()))for(JsonNode item:node.path("children"))instantiateItem((ObjectNode)item,seed);else instantiateItem((ObjectNode)node,seed);
        }return DocumentCodec.parse(tree);
    }
    private static void instantiateItem(ObjectNode item,long seed) {
        JsonNode specs=item.path("interaction").path("variables");if(!specs.isArray() || specs.isEmpty())return;
        Random random=new Random(seed^item.path("id").asText().hashCode());Map<String,Double> values=new LinkedHashMap<>();Map<String,String> texts=new LinkedHashMap<>();
        for(JsonNode spec:specs){String id=spec.path("id").asText();double min=spec.path("min").asDouble(),max=spec.path("max").asDouble(),step=spec.path("step").asDouble(1);long count=(long)Math.floor((max-min)/step)+1;
            if(count<1 || count>10000000 || step<=0 || !Double.isFinite(min+max+step))throw new AppException(ErrorCode.BAD_REQUEST,"Khoảng biến số không hợp lệ");
            double value=min+random.nextLong(count)*step;values.put(id,value);texts.put(id,BigDecimal.valueOf(value).stripTrailingZeros().toPlainString());
        }
        ObjectNode replaced=(ObjectNode)replace(item,texts);item.removeAll();item.setAll(replaced);
        if("Q11".equals(item.path("type").asText()) && item.path("answerKey").hasNonNull("expression")) {
            try{double answer=SafeExpression.evaluate(item.path("answerKey").path("expression").asText(),values);((ObjectNode)item.get("answerKey")).put("value",answer);}
            catch(IllegalArgumentException e){throw new AppException(ErrorCode.BAD_REQUEST,"Công thức đáp án không xác định với biến số được sinh");}
        }
    }
    private static JsonNode replace(JsonNode value,Map<String,String> vars){
        if(value.isTextual()){String text=value.asText();for(var v:vars.entrySet())text=text.replace("{{"+v.getKey()+"}}",v.getValue());return TextNode.valueOf(text);}
        if(value.isArray()){ArrayNode out=DocumentCodec.mapper().createArrayNode();value.forEach(v->out.add(replace(v,vars)));return out;}
        if(value.isObject()){ObjectNode out=DocumentCodec.mapper().createObjectNode();value.fields().forEachRemaining(e->out.set(e.getKey(),"variables".equals(e.getKey())?e.getValue().deepCopy():replace(e.getValue(),vars)));return out;}return value.deepCopy();
    }
}
