package com.classroom.modules.assessment.doc;

import com.classroom.modules.assessment.doc.AssessmentInterchange.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.w3c.dom.*;
import java.util.*;
import java.util.regex.*;

/** DOCX is parsed as content, never as active Office markup or external relationships. */
final class AssessmentWordImport {
    private static final Pattern MARKER=Pattern.compile("\\[\\[classroom-(image|table):([A-Za-z0-9_-]+)]]");
    private final Map<String,byte[]> files;
    private final List<String> warnings;
    private final Map<String,String> relationships=new HashMap<>();
    private final Map<String,EmbeddedAsset> embedded=new LinkedHashMap<>();
    private final Map<String,AssetRef> assets=new LinkedHashMap<>();
    private final Map<String,JsonNode> tables=new HashMap<>();
    private final Map<String,String> imageKeys=new HashMap<>();
    private AssessmentWordImport(Map<String,byte[]> files,List<String> warnings){this.files=files;this.warnings=warnings;}
    static Imported read(Map<String,byte[]> files,List<String> warnings){return new AssessmentWordImport(files,warnings).parse();}
    private Imported parse(){
        if(files.containsKey("word/_rels/document.xml.rels")){
            NodeList rels=AssessmentInterchange.xml(files.get("word/_rels/document.xml.rels")).getElementsByTagNameNS("*","Relationship");
            for(int i=0;i<rels.getLength();i++){Element e=(Element)rels.item(i);if(!"External".equalsIgnoreCase(e.getAttribute("TargetMode")))relationships.put(e.getAttribute("Id"),e.getAttribute("Target"));}
        }
        org.w3c.dom.Document xml=AssessmentInterchange.xml(files.get("word/document.xml"));Element body=(Element)xml.getElementsByTagNameNS("*","body").item(0);List<String> lines=new ArrayList<>();
        for(org.w3c.dom.Node node=body.getFirstChild();node!=null;node=node.getNextSibling()){
            if(!(node instanceof Element element))continue;
            if("p".equals(element.getLocalName())){String text=rich(element);if(!text.isBlank())lines.add(text);}
            else if("tbl".equals(element.getLocalName())){
                ArrayNode rows=DocumentCodec.mapper().createArrayNode();for(Element row:children(element,"tr")){ArrayNode cells=rows.addArray();for(Element cell:children(row,"tc"))cells.add(rich(cell).trim());}
                String key="table"+tables.size();ObjectNode table=DocumentCodec.mapper().createObjectNode();table.put("type","TABLE");table.put("header",false);table.set("rows",rows);tables.put(key,table);lines.add("[[classroom-table:"+key+"]]");
            }
        }
        AssessmentDocument doc=AssessmentInterchange.fromText(lines,warnings);ObjectNode tree=(ObjectNode)DocumentCodec.toTree(doc);
        for(JsonNode section:tree.path("sections"))for(JsonNode node:section.path("nodes")){
            if("GROUP".equals(node.path("kind").asText())){ArrayNode stimulus=DocumentCodec.mapper().createArrayNode();for(JsonNode block:node.path("stimulus")){String text=block.path("text").asText();for(JsonNode b:blocks(text))stimulus.add(b);}((ObjectNode)node).set("stimulus",stimulus);for(JsonNode q:node.path("children"))item((ObjectNode)q);}
            else item((ObjectNode)node);
        }
        tree.set("assets",DocumentCodec.toTree(assets));warnings.add("Ảnh nhúng, bảng và công thức đã được chuyển sang nội dung đề; kiểm tra ngữ liệu, đáp án và điểm trước khi giao.");
        return new Imported(DocumentCodec.parse(tree),List.copyOf(warnings),Map.copyOf(embedded));
    }
    private void item(ObjectNode q){
        String prompt=q.path("prompt").asText();q.put("prompt",MARKER.matcher(prompt).replaceAll("").trim());ArrayNode content=DocumentCodec.mapper().createArrayNode();
        for(JsonNode block:blocks(prompt))if(!"TEXT".equals(block.path("type").asText()))content.add(block);q.set("promptBlocks",content);
        for(JsonNode option:q.path("interaction").path("options")){String text=option.path("text").asText();Matcher marker=MARKER.matcher(text);if(marker.find() && marker.group(1).equals("image"))((ObjectNode)option).put("assetKey",marker.group(2));((ObjectNode)option).put("text",MARKER.matcher(text).replaceAll("").trim());}
    }
    private List<JsonNode> blocks(String value){List<JsonNode> result=new ArrayList<>();Matcher match=MARKER.matcher(value);int last=0;while(match.find()){
        if(match.start()>last)result.add(DocumentCodec.toTree(ContentBlock.text(value.substring(last,match.start()))));
        if(match.group(1).equals("table"))result.add(tables.get(match.group(2)));else{ObjectNode image=DocumentCodec.mapper().createObjectNode();image.put("type","IMAGE");image.put("assetKey",match.group(2));image.put("alt",assets.get(match.group(2)).name());result.add(image);}last=match.end();
    }if(last<value.length())result.add(DocumentCodec.toTree(ContentBlock.text(value.substring(last))));return result;}
    private String rich(org.w3c.dom.Node node){if(node instanceof Element e){String name=e.getLocalName();
        if("oMath".equals(name))return "$"+math(e)+"$";
        if("blip".equals(name))return image(e.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","embed"));
        if("t".equals(name))return e.getTextContent();if("tab".equals(name))return "\t";if("br".equals(name))return "\n";
    }StringBuilder text=new StringBuilder();for(org.w3c.dom.Node child=node.getFirstChild();child!=null;child=child.getNextSibling())text.append(rich(child));if(node instanceof Element e && "p".equals(e.getLocalName()))text.append("\n");return text.toString();}
    private String image(String id){
        String previous=imageKeys.get(id);if(previous!=null)return "[[classroom-image:"+previous+"]]";
        String target=relationships.get(id);if(target==null){warnings.add("Một ảnh liên kết bên ngoài đã bị bỏ qua; hệ thống không tải URL bên ngoài.");return "[Ảnh liên kết cần đính kèm]";}
        String path=java.nio.file.Path.of("word").resolve(target).normalize().toString().replace('\\','/');byte[] bytes=files.get(path);String lower=path.toLowerCase(Locale.ROOT);String mime=lower.endsWith(".png")?"image/png":lower.endsWith(".jpg")||lower.endsWith(".jpeg")?"image/jpeg":lower.endsWith(".gif")?"image/gif":lower.endsWith(".webp")?"image/webp":null;
        if(bytes==null || mime==null || assets.size()>=200){warnings.add("Ảnh ‘"+target+"’ cần chuyển thành PNG/JPG/WebP/GIF và đính kèm lại.");return "[Ảnh cần đính kèm: "+target+"]";}
        String key="wordimg"+assets.size(),name=path.substring(path.lastIndexOf('/')+1);imageKeys.put(id,key);embedded.put(key,new EmbeddedAsset(name,mime,bytes));
        ObjectNode asset=DocumentCodec.mapper().createObjectNode();asset.put("assetId",UUID.randomUUID().toString());asset.put("type","IMAGE");asset.put("name",name);asset.put("mime",mime);asset.put("sizeBytes",bytes.length);assets.put(key,DocumentCodec.mapper().convertValue(asset,AssetRef.class));return "[[classroom-image:"+key+"]]";
    }
    private String math(Element e){String name=e.getLocalName();return switch(name){
        case "f"->"\\frac{"+part(e,"num")+"}{"+part(e,"den")+"}";
        case "sSup"->"{"+part(e,"e")+"}^{"+part(e,"sup")+"}";
        case "sSub"->"{"+part(e,"e")+"}_{"+part(e,"sub")+"}";
        case "sSubSup"->"{"+part(e,"e")+"}_{"+part(e,"sub")+"}^{"+part(e,"sup")+"}";
        case "rad"->"\\sqrt"+(part(e,"deg").isBlank()?"":"["+part(e,"deg")+"]")+"{"+part(e,"e")+"}";
        case "d"->"\\left("+part(e,"e")+"\\right)";
        case "t"->e.getTextContent().replace("×","\\times ").replace("÷","\\div ").replace("≤","\\le ").replace("≥","\\ge ");
        default->{StringBuilder b=new StringBuilder();for(org.w3c.dom.Node node=e.getFirstChild();node!=null;node=node.getNextSibling())if(node instanceof Element child && !child.getLocalName().endsWith("Pr"))b.append(math(child));yield b.toString();}
    };}
    private String part(Element e,String name){return children(e,name).stream().map(this::math).reduce("",String::concat);}
    private static List<Element> children(Element e,String name){List<Element> out=new ArrayList<>();for(org.w3c.dom.Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element child && name.equals(child.getLocalName()))out.add(child);return out;}
}
