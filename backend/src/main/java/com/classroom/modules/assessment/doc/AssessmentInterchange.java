package com.classroom.modules.assessment.doc;

import com.classroom.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Bounded, dependency-free OOXML/QTI interchange. No external XML resources are resolved. */
public final class AssessmentInterchange {
    private AssessmentInterchange(){}
    public record EmbeddedAsset(String name,String mime,byte[] bytes){}
    public record Imported(AssessmentDocument document,List<String> warnings,Map<String,EmbeddedAsset> embeddedAssets){public Imported(AssessmentDocument document,List<String> warnings){this(document,warnings,Map.of());}}
    public record Exported(byte[] bytes,String mime,String extension,List<String> warnings){}
    public static Imported read(byte[] bytes,String filename){
        if(bytes.length==0 || bytes.length>10*1024*1024)bad("Tệp import tối đa 10 MB");
        String name=filename.toLowerCase(Locale.ROOT);List<String> warnings=new ArrayList<>();AssessmentDocument doc;
        if(name.endsWith(".json"))doc=DocumentCodec.parse(new String(bytes,StandardCharsets.UTF_8));
        else if(name.endsWith(".csv"))doc=fromRows(csv(new String(bytes,StandardCharsets.UTF_8)),warnings);
        else if(name.endsWith(".xlsx"))doc=fromRows(xlsxRows(unzip(bytes)),warnings);
        else if(name.endsWith(".docx")){
            Map<String,byte[]> entries=unzip(bytes);byte[] main=entries.get("word/document.xml");if(main==null)bad("Không phải tệp Word DOCX");
            List<String> paragraphs=paragraphs(xml(main));String plain=String.join("\n",paragraphs);
            if(entries.containsKey("classroom/document.json") && entries.containsKey("classroom/body.sha256") && hash(plain).equals(new String(entries.get("classroom/body.sha256"),StandardCharsets.UTF_8)))
                doc=DocumentCodec.parse(new String(entries.get("classroom/document.json"),StandardCharsets.UTF_8));
            else return AssessmentWordImport.read(entries,warnings);
        } else if(name.endsWith(".zip") || name.endsWith(".xml")) {
            Map<String,byte[]> entries=name.endsWith(".xml")?Map.of("item.xml",bytes):unzip(bytes);
            if(entries.containsKey("classroom/document.json"))doc=DocumentCodec.parse(new String(entries.get("classroom/document.json"),StandardCharsets.UTF_8));
            else doc=fromQti(entries,warnings);
        } else throw new AppException(ErrorCode.BAD_REQUEST,"Hỗ trợ JSON, CSV, XLSX, DOCX và QTI ZIP/XML");
        return new Imported(doc,List.copyOf(warnings));
    }
    public static Exported write(AssessmentDocument doc,String format){
        List<String> warnings=new ArrayList<>();
        return switch(format.toLowerCase(Locale.ROOT)){
            case "json"->new Exported(DocumentCodec.write(doc).getBytes(StandardCharsets.UTF_8),"application/json","json",List.of());
            case "csv"->new Exported(toCsv(toRows(doc)).getBytes(StandardCharsets.UTF_8),"text/csv;charset=UTF-8","csv",List.of());
            case "xlsx"->new Exported(toXlsx(toRows(doc)),"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","xlsx",List.of());
            case "docx"->new Exported(toDocx(doc),"application/vnd.openxmlformats-officedocument.wordprocessingml.document","docx",List.of());
            case "qti"->new Exported(toQti(doc,warnings),"application/zip","zip",List.copyOf(warnings));
            default->throw new AppException(ErrorCode.BAD_REQUEST,"Định dạng xuất không hợp lệ");
        };
    }
    public static byte[] template(String format){
        AssessmentDocument doc=fromText(List.of("Đề mẫu import","Câu 1: 2 + 3 bằng bao nhiêu?","A. 4","B. 5","C. 6","Đáp án: B","Câu 2: Viết một đoạn văn về gia đình."),new ArrayList<>());
        return write(doc,format).bytes();
    }
    static org.w3c.dom.Document xml(byte[] bytes){
        try {DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var builder=factory.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){@Override public void fatalError(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}});
            return builder.parse(new ByteArrayInputStream(bytes));
        }catch(Exception e){throw new AppException(ErrorCode.BAD_REQUEST,"XML không hợp lệ hoặc chứa nội dung không được phép");}
    }
    static Map<String,byte[]> unzip(byte[] bytes){
        Map<String,byte[]> result=new LinkedHashMap<>();int total=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){String name=entry.getName();if(name.startsWith("/") || name.contains("\\") || Arrays.asList(name.split("/")).contains("..") || result.size()>=500 || result.containsKey(name))bad("Cấu trúc ZIP không hợp lệ");
                if(entry.isDirectory())continue;byte[] data=zip.readNBytes(5*1024*1024+1);total+=data.length;
                if(data.length>5*1024*1024 || total>20*1024*1024)bad("Nội dung giải nén vượt giới hạn 20 MB");result.put(name,data);
            }
        }catch(IOException e){throw new AppException(ErrorCode.BAD_REQUEST,"Không đọc được ZIP");}return result;
    }
    static byte[] zip(Map<String,String> entries){try{ByteArrayOutputStream output=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(output)){for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}return output.toByteArray();}catch(IOException e){throw new IllegalStateException(e);}}
    private static String esc(String value){return Objects.toString(value,"").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private static List<String> paragraphs(org.w3c.dom.Document doc){List<String> list=new ArrayList<>();NodeList nodes=doc.getElementsByTagNameNS("*","p");for(int i=0;i<nodes.getLength();i++){Element p=(Element)nodes.item(i);NodeList texts=p.getElementsByTagNameNS("*","t");StringBuilder text=new StringBuilder();for(int j=0;j<texts.getLength();j++)text.append(texts.item(j).getTextContent());if(!text.isEmpty())list.add(text.toString());}return list;}
    private static String hash(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static ItemNode basic(String prompt,List<String> options,String answer){
        ObjectNode x=DocumentCodec.mapper().createObjectNode(),key=DocumentCodec.mapper().createObjectNode();String type=options.isEmpty()?"Q12":"Q01";
        if(!options.isEmpty()){ArrayNode array=x.putArray("options");for(int i=0;i<options.size();i++)array.addObject().put("id","o"+i).put("text",options.get(i));
            Matcher m=Pattern.compile("[A-Z]",Pattern.CASE_INSENSITIVE).matcher(answer);List<String> correct=new ArrayList<>();while(m.find()){int index=Character.toUpperCase(m.group().charAt(0))-'A';if(index<options.size())correct.add("o"+index);}
            if(correct.size()>1){type="Q02";key.set("optionIds",DocumentCodec.toTree(correct));}else if(correct.size()==1)key.put("optionId",correct.get(0));}
        return new ItemNode(UUID.randomUUID().toString(),type,prompt,List.of(),BigDecimal.ONE,new GradingSpec(options.isEmpty()?"MANUAL":"AUTO",false,null,null,null),null,null,null,List.of(),x,key);
    }
    static AssessmentDocument fromText(List<String> paragraphs,List<String> warnings){
        List<Node> nodes=new ArrayList<>();String title="Đề import",prompt=null,answer="",stimulus=null;List<String> options=new ArrayList<>();int skipped=0;
        for(String line:paragraphs){String value=line.trim();Matcher question=Pattern.compile("^(?:Câu|Question)\\s*\\d+\\s*[:.)-]\\s*(.*)$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE|Pattern.DOTALL).matcher(value);
            Matcher option=Pattern.compile("^[A-Z][.)]\\s*(.*)$").matcher(value);
            if(question.matches()){if(prompt!=null)addTextItem(nodes,basic(prompt,options,answer),stimulus);prompt=question.group(1);options=new ArrayList<>();answer="";}
            else if(value.matches("(?iu)^(Đáp án|Answer|Key)\\s*:.*"))answer=value.substring(value.indexOf(':')+1).trim();
            else if(value.matches("(?iu)^(Ngữ liệu|Passage)\\s*:.*"))stimulus=value.substring(value.indexOf(':')+1).trim();
            else if(prompt!=null && option.matches())options.add(option.group(1));
            else if(prompt!=null)prompt+="\n"+value;
            else if(skipped++==0)title=value;
        }
        if(prompt!=null)addTextItem(nodes,basic(prompt,options,answer),stimulus);if(nodes.isEmpty())bad("Không tìm thấy câu hỏi. Dùng dòng ‘Câu 1: …’, phương án ‘A. …’, ‘Đáp án: B’ như tệp mẫu.");
        return new AssessmentDocument(1,title,"HOMEWORK",null,null,null,null,Map.of(),List.of(new SectionNode("import-section","Phần I","",false,nodes)));
    }
    private static void addTextItem(List<Node> nodes,ItemNode item,String stimulus){
        if(stimulus==null || stimulus.isBlank()){nodes.add(item);return;}
        if(!nodes.isEmpty() && nodes.get(nodes.size()-1) instanceof GroupNode g && g.stimulus().get(0).text().equals(stimulus)){List<ItemNode> children=new ArrayList<>(g.children());children.add(item);nodes.set(nodes.size()-1,new GroupNode(g.id(),g.groupType(),g.title(),g.instructions(),g.stimulus(),null,g.playback(),true,"ALL",false,children));}
        else nodes.add(new GroupNode(UUID.randomUUID().toString(),"Q13","Ngữ liệu chung","",List.of(ContentBlock.text(stimulus)),null,null,true,"ALL",false,List.of(item)));
    }
    private static List<List<String>> toRows(AssessmentDocument doc){
        List<List<String>> rows=new ArrayList<>();rows.add(List.of("kind","id","title","type","prompt","points","options","answer","data"));
        rows.add(Arrays.asList("META","",doc.title(),"","","","","",DocumentCodec.write(doc.withSections(List.of()))));
        for(SectionNode section:doc.sections()){rows.add(Arrays.asList("SECTION",section.id(),section.title(),"","","","","",DocumentCodec.writeJson(new SectionNode(section.id(),section.title(),section.instructions(),section.shuffle(),List.of(),section.audioAssetKey(),section.playback()))));
            for(Node node:section.nodes()){if(node instanceof GroupNode g){rows.add(Arrays.asList("GROUP",g.id(),g.title(),g.groupType(),"","","","",DocumentCodec.writeJson(new GroupNode(g.id(),g.groupType(),g.title(),g.instructions(),g.stimulus(),g.audioAssetKey(),g.playback(),g.keepStimulusVisible(),g.display(),g.shuffleChildren(),List.of()))));for(ItemNode item:g.children())rows.add(itemRow(item));rows.add(List.of("END_GROUP"));}else rows.add(itemRow((ItemNode)node));}
        }return rows;
    }
    private static List<String> itemRow(ItemNode item){String options="",answer="";if(item.interaction().path("options").isArray()){List<String> values=new ArrayList<>();for(JsonNode option:item.interaction().path("options"))values.add(option.path("text").asText());options=String.join(" | ",values);
            List<String> correct=new ArrayList<>();for(int i=0;i<item.interaction().path("options").size();i++){String id=item.interaction().path("options").get(i).path("id").asText();if(item.answerKey().path("optionId").asText().equals(id) || DocumentCodec.writeJson(item.answerKey().path("optionIds")).contains("\""+id+"\""))correct.add(String.valueOf((char)('A'+i)));}answer=String.join(",",correct);}
        return Arrays.asList("ITEM",item.id(),"",item.type(),item.prompt(),item.points().toPlainString(),options,answer,DocumentCodec.writeJson(item));}
    private static AssessmentDocument fromRows(List<List<String>> rows,List<String> warnings){
        if(rows.size()<2)bad("Bảng import không có câu hỏi");List<String> headers=rows.get(0).stream().map(s->s.replace("\uFEFF","").trim().toLowerCase(Locale.ROOT)).toList();
        AssessmentDocument meta=AssessmentDocument.empty("Đề import","HOMEWORK",null,null,null,null);List<SectionNode> sections=new ArrayList<>();SectionNode section=new SectionNode("import-section","Phần I","",false,List.of());List<Node> nodes=new ArrayList<>();GroupNode group=null;List<ItemNode> children=new ArrayList<>();
        for(int r=1;r<rows.size();r++){List<String> row=rows.get(r);Map<String,String> values=new HashMap<>();for(int c=0;c<headers.size();c++)values.put(headers.get(c),c<row.size()?unescapeCsv(row.get(c)):"");String kind=values.getOrDefault("kind","ITEM"),data=values.getOrDefault("data","");
            if(kind.equals("META")){if(!data.isBlank())meta=DocumentCodec.parse(data);continue;}
            if(kind.equals("SECTION")){if(group!=null){nodes.add(withChildren(group,children));group=null;children=new ArrayList<>();}if(!nodes.isEmpty() || !sections.isEmpty())sections.add(new SectionNode(section.id(),section.title(),section.instructions(),section.shuffle(),nodes,section.audioAssetKey(),section.playback()));nodes=new ArrayList<>();section=data.isBlank()?new SectionNode(UUID.randomUUID().toString(),values.getOrDefault("title","Phần"),"",false,List.of()):DocumentCodec.read(data,SectionNode.class);continue;}
            if(kind.equals("GROUP") || kind.equals("END_GROUP")){if(group!=null){nodes.add(withChildren(group,children));children=new ArrayList<>();group=null;}if(kind.equals("GROUP")){if(data.isBlank())bad("Nhóm thiếu dữ liệu ngữ liệu");group=DocumentCodec.read(data,GroupNode.class);}continue;}
            if(!kind.equals("ITEM") || values.getOrDefault("prompt","").isBlank())continue;
            ItemNode item;
            if(data.isBlank()){List<String> options=values.getOrDefault("options","").isBlank()?List.of():Arrays.stream(values.get("options").split("\\|")).map(String::trim).toList();item=basic(values.get("prompt"),options,values.getOrDefault("answer",""));}
            else {ObjectNode tree=(ObjectNode)DocumentCodec.readTree(data);tree.put("prompt",values.get("prompt"));tree.put("type",values.getOrDefault("type",tree.path("type").asText()));
                if(!values.getOrDefault("points","").isBlank())try{tree.put("points",new BigDecimal(values.get("points")));}catch(NumberFormatException e){bad("Điểm không hợp lệ tại dòng "+(r+1));}
                item=DocumentCodec.mapper().convertValue(tree,ItemNode.class);
                if(Set.of("Q01","Q02").contains(item.type()) && !values.getOrDefault("options","").isBlank()){
                    List<String> texts=Arrays.stream(values.get("options").split("\\|")).map(String::trim).toList();JsonNode original=item.interaction().path("options");boolean changed=texts.size()!=original.size();for(int i=0;i<Math.min(texts.size(),original.size());i++)if(!texts.get(i).equals(original.get(i).path("text").asText()))changed=true;
                    if(changed || !values.getOrDefault("answer","").equals(itemRow(item).get(7))){ItemNode updated=basic(item.prompt(),texts,values.getOrDefault("answer",""));tree.set("interaction",updated.interaction());tree.set("answerKey",updated.answerKey());tree.put("type",updated.type());item=DocumentCodec.mapper().convertValue(tree,ItemNode.class);}
                }
            }
            if(group==null)nodes.add(item);else children.add(item);
        }
        if(group!=null)nodes.add(withChildren(group,children));sections.add(new SectionNode(section.id(),section.title(),section.instructions(),section.shuffle(),nodes,section.audioAssetKey(),section.playback()));return meta.withSections(sections);
    }
    private static GroupNode withChildren(GroupNode g,List<ItemNode> children){return new GroupNode(g.id(),g.groupType(),g.title(),g.instructions(),g.stimulus(),g.audioAssetKey(),g.playback(),g.keepStimulusVisible(),g.display(),g.shuffleChildren(),children);}
    private static String toCsv(List<List<String>> rows){return "\uFEFF"+String.join("\r\n",rows.stream().map(row->String.join(",",row.stream().map(s->"\""+escapeCsv(s).replace("\"","\"\"")+"\"").toList())).toList());}
    private static String escapeCsv(String s){return s.matches("(?s)^[\\s]*[=+\\-@].*")?"'"+s:s;}
    private static String unescapeCsv(String s){return s.matches("(?s)^'[\\s]*[=+\\-@].*")?s.substring(1):s;}
    static List<List<String>> csv(String text){List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;char delimiter=text.lines().findFirst().orElse("").contains("\t")?'\t':',';
        for(int i=0;i<text.length();i++){char ch=text.charAt(i);if(ch=='"'){if(quoted && i+1<text.length() && text.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=!quoted;}else if(!quoted && ch==delimiter){row.add(cell.toString());cell.setLength(0);}else if(!quoted && (ch=='\n' || ch=='\r')){if(ch=='\r' && i+1<text.length() && text.charAt(i+1)=='\n')i++;row.add(cell.toString());cell.setLength(0);rows.add(row);row=new ArrayList<>();}else cell.append(ch);}
        if(quoted)bad("CSV có dấu ngoặc kép chưa đóng");if(!cell.isEmpty() || !row.isEmpty()){row.add(cell.toString());rows.add(row);}return rows;}
    private static List<List<String>> xlsxRows(Map<String,byte[]> files){
        byte[] sheet=files.get("xl/worksheets/sheet1.xml");if(sheet==null)bad("Không tìm thấy sheet1 trong XLSX");List<String> shared=new ArrayList<>();if(files.containsKey("xl/sharedStrings.xml")){NodeList strings=xml(files.get("xl/sharedStrings.xml")).getElementsByTagNameNS("*","si");for(int i=0;i<strings.getLength();i++)shared.add(strings.item(i).getTextContent());}
        List<List<String>> rows=new ArrayList<>();NodeList rowNodes=xml(sheet).getElementsByTagNameNS("*","row");if(rowNodes.getLength()>2000)bad("Bảng import tối đa 2.000 dòng");
        for(int i=0;i<rowNodes.getLength();i++){List<String> row=new ArrayList<>();NodeList cells=((Element)rowNodes.item(i)).getElementsByTagNameNS("*","c");for(int j=0;j<cells.getLength();j++){Element cell=(Element)cells.item(j);String ref=cell.getAttribute("r").replaceAll("[0-9]","");int index=0;for(char ch:ref.toCharArray())index=index*26+(ch-'A'+1);index=Math.max(0,index-1);if(index>30)bad("Bảng import tối đa 31 cột");while(row.size()<=index)row.add("");String type=cell.getAttribute("t"),value;
                if(type.equals("inlineStr"))value=cell.getTextContent();else {NodeList v=cell.getElementsByTagNameNS("*","v");value=v.getLength()==0?"":v.item(0).getTextContent();if(type.equals("s")){try{value=shared.get(Integer.parseInt(value));}catch(Exception e){bad("Chuỗi XLSX không hợp lệ");}}}
                row.set(index,value);
            }rows.add(row);}
        return rows;
    }
    private static byte[] toXlsx(List<List<String>> rows){
        StringBuilder sheet=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for(int r=0;r<rows.size();r++){sheet.append("<row r=\"").append(r+1).append("\">");for(int c=0;c<rows.get(r).size();c++)sheet.append("<c r=\"").append((char)('A'+c)).append(r+1).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(esc(rows.get(r).get(c))).append("</t></is></c>");sheet.append("</row>");}sheet.append("</sheetData></worksheet>");
        return zip(Map.of("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>",
            "_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
            "xl/workbook.xml","<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Cau hoi\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>","xl/worksheets/sheet1.xml",sheet.toString()));
    }
    private static byte[] toDocx(AssessmentDocument doc){
        List<String> text=new ArrayList<>();text.add(doc.title());DocumentIndex index=DocumentIndex.of(doc);
        for(SectionNode section:doc.sections()){text.add(section.title());if(!section.instructions().isBlank())text.add(section.instructions());for(Node node:section.nodes()){
            if(node instanceof GroupNode g){text.add("Ngữ liệu: "+g.title());for(ContentBlock block:g.stimulus())if(block.text()!=null)text.add(block.text());for(ItemNode item:g.children())wordItem(text,item,index.label(item.id()));}
            else wordItem(text,(ItemNode)node,index.label(((ItemNode)node).id()));}}
        StringBuilder body=new StringBuilder("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");for(String p:text)body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(esc(p)).append("</w:t></w:r></w:p>");body.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr></w:body></w:document>");
        return zip(Map.of("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"json\" ContentType=\"application/json\"/><Default Extension=\"sha256\" ContentType=\"text/plain\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>","_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>","word/document.xml",body.toString(),"classroom/document.json",DocumentCodec.write(doc),"classroom/body.sha256",hash(String.join("\n",text))));
    }
    private static void wordItem(List<String> text,ItemNode item,String label){text.add(label+": "+item.prompt());List<String> row=itemRow(item);if(!row.get(6).isBlank()){String[] options=row.get(6).split(" \\| ");for(int i=0;i<options.length;i++)text.add((char)('A'+i)+". "+options[i]);if(!row.get(7).isBlank())text.add("Đáp án: "+row.get(7));}else if(!item.answerKey().isEmpty())text.add("Đáp án: "+DocumentCodec.writeJson(item.answerKey()));}
    private static AssessmentDocument fromQti(Map<String,byte[]> entries,List<String> warnings){
        List<Node> items=new ArrayList<>();for(var entry:entries.entrySet()){if(!entry.getKey().endsWith(".xml") || entry.getKey().equals("imsmanifest.xml"))continue;org.w3c.dom.Document doc=xml(entry.getValue());Element root=doc.getDocumentElement();if(!"assessmentItem".equals(root.getLocalName()))continue;
            NodeList choice=doc.getElementsByTagNameNS("*","choiceInteraction"),text=doc.getElementsByTagNameNS("*","textEntryInteraction"),essay=doc.getElementsByTagNameNS("*","extendedTextInteraction");
            if(choice.getLength()+text.getLength()+essay.getLength()!=1){warnings.add("Bỏ qua câu QTI ‘"+root.getAttribute("title")+"’: tương tác chưa hỗ trợ.");continue;}
            Element interaction=(Element)(choice.getLength()>0?choice.item(0):text.getLength()>0?text.item(0):essay.item(0));String responseId=interaction.getAttribute("responseIdentifier");List<String> keyValues=new ArrayList<>();
            NodeList declarations=doc.getElementsByTagNameNS("*","responseDeclaration");for(int d=0;d<declarations.getLength();d++){Element declaration=(Element)declarations.item(d);if(declaration.getAttribute("identifier").equals(responseId)){NodeList correct=declaration.getElementsByTagNameNS("*","correctResponse");if(correct.getLength()>0){NodeList values=((Element)correct.item(0)).getElementsByTagNameNS("*","value");for(int v=0;v<values.getLength();v++)keyValues.add(values.item(v).getTextContent());}}}
            ObjectNode x=DocumentCodec.mapper().createObjectNode(),key=DocumentCodec.mapper().createObjectNode();String type,mode="AUTO";String prompt=root.getAttribute("title");NodeList prompts=doc.getElementsByTagNameNS("*","prompt");if(prompts.getLength()>0)prompt=prompts.item(0).getTextContent();else {NodeList p=doc.getElementsByTagNameNS("*","p");if(p.getLength()>0)prompt=p.item(0).getTextContent();}
            if(choice.getLength()>0){boolean multiple=!interaction.getAttribute("maxChoices").equals("1");type=multiple?"Q02":"Q01";ArrayNode options=x.putArray("options");NodeList choices=interaction.getElementsByTagNameNS("*","simpleChoice");Map<String,String> ids=new HashMap<>();for(int c=0;c<choices.getLength();c++){Element option=(Element)choices.item(c);String id="o"+c;ids.put(option.getAttribute("identifier"),id);options.addObject().put("id",id).put("text",option.getTextContent());}if(multiple)key.set("optionIds",DocumentCodec.toTree(keyValues.stream().map(ids::get).filter(Objects::nonNull).toList()));else if(!keyValues.isEmpty())key.put("optionId",ids.get(keyValues.get(0)));}
            else if(text.getLength()>0){type="Q10";key.set("accepted",DocumentCodec.toTree(keyValues));}else{type="Q12";mode="MANUAL";}
            items.add(new ItemNode(UUID.randomUUID().toString(),type,prompt,List.of(),qtiPoints(doc),new GradingSpec(mode,false,null,null,null),null,null,null,List.of(),x,key));
        }
        if(items.isEmpty())bad("Không tìm thấy câu QTI hỗ trợ (choice, textEntry, extendedText)");warnings.add("QTI được đọc theo tương tác cơ bản; kiểm tra điểm, thứ tự phần và quy tắc chấm tùy chỉnh trước khi giao.");
        return new AssessmentDocument(1,"Đề QTI import","HOMEWORK",null,null,null,null,Map.of(),List.of(new SectionNode("qti-section","Phần I","",false,items)));
    }
    private static byte[] toQti(AssessmentDocument doc,List<String> warnings){
        Map<String,String> files=new LinkedHashMap<>();String ns="http://www.imsglobal.org/xsd/imsqti_v2p1";StringBuilder resources=new StringBuilder(),refs=new StringBuilder(),dependencies=new StringBuilder();int number=0;
        for(var ref:DocumentIndex.of(doc).items()){ItemNode item=ref.item();if(!Set.of("Q01","Q02","Q10","Q12").contains(item.type())){warnings.add("Câu "+ref.item().type()+" giữ trong classroom/document.json; LMS QTI cần nhập riêng.");continue;}if(item.grading().partialCredit() || !"AUTO".equals(item.mode()) || item.grading().normalization()!=null && (item.grading().normalization().collapseWhitespace() || item.grading().normalization().stripTrailingPunctuation()))warnings.add("Câu "+ref.item().id()+": QTI dùng chấm toàn phần; quy tắc chấm riêng giữ trong bản JSON.");number++;String id="item"+number,path=id+".xml";boolean multiple=item.type().equals("Q02"),essay=item.type().equals("Q12"),choice=Set.of("Q01","Q02").contains(item.type());
            List<String> values=new ArrayList<>();if(choice){if(multiple)for(JsonNode key:item.answerKey().path("optionIds"))values.add(key.asText());else values.add(item.answerKey().path("optionId").asText());}else if(!essay)for(JsonNode key:item.answerKey().path("accepted"))values.add(key.asText());
            StringBuilder xml=new StringBuilder("<assessmentItem xmlns=\"").append(ns).append("\" identifier=\"").append(id).append("\" title=\"").append(esc(item.prompt())).append("\" adaptive=\"false\" timeDependent=\"false\"><responseDeclaration identifier=\"RESPONSE\" cardinality=\"").append(multiple?"multiple":"single").append("\" baseType=\"").append(choice?"identifier":"string").append("\">");if(!values.isEmpty()){xml.append("<correctResponse>");for(String value:choice?values:values.subList(0,Math.min(1,values.size())))xml.append("<value>").append(esc(value)).append("</value>");xml.append("</correctResponse>");}xml.append("</responseDeclaration><outcomeDeclaration identifier=\"SCORE\" cardinality=\"single\" baseType=\"float\" normalMaximum=\"").append(item.points()).append("\"/><itemBody>");
            if(ref.group()!=null){for(ContentBlock block:ref.group().stimulus())if(block.text()!=null)xml.append("<p>").append(esc(block.text())).append("</p>");}
            if(choice){xml.append("<choiceInteraction responseIdentifier=\"RESPONSE\" shuffle=\"false\" maxChoices=\"").append(multiple?"0":"1").append("\"><prompt>").append(esc(item.prompt())).append("</prompt>");for(JsonNode option:item.interaction().path("options"))xml.append("<simpleChoice identifier=\"").append(esc(option.path("id").asText())).append("\">").append(esc(option.path("text").asText())).append("</simpleChoice>");xml.append("</choiceInteraction>");}
            else xml.append("<p>").append(esc(item.prompt())).append("</p><").append(essay?"extendedTextInteraction":"textEntryInteraction").append(" responseIdentifier=\"RESPONSE\"/>");xml.append("</itemBody>");if(!essay)xml.append(qtiProcessing(item,values,choice));xml.append("</assessmentItem>");files.put(path,xml.toString());
            dependencies.append("<dependency identifierref=\"").append(id).append("\"/>");
            resources.append("<resource identifier=\"").append(id).append("\" type=\"imsqti_item_xmlv2p1\" href=\"").append(path).append("\"><file href=\"").append(path).append("\"/></resource>");refs.append("<assessmentItemRef identifier=\"ref").append(number).append("\" href=\"").append(path).append("\"/>");
        }
        files.put("test.xml","<assessmentTest xmlns=\""+ns+"\" identifier=\"test\" title=\""+esc(doc.title())+"\"><testPart identifier=\"part\" navigationMode=\"linear\" submissionMode=\"individual\"><assessmentSection identifier=\"section\" title=\""+esc(doc.title())+"\" visible=\"true\">"+refs+"</assessmentSection></testPart></assessmentTest>");
        files.put("imsmanifest.xml","<manifest xmlns=\"http://www.imsglobal.org/xsd/imscp_v1p1\" identifier=\"classroom\"><organizations/><resources><resource identifier=\"test\" type=\"imsqti_test_xmlv2p1\" href=\"test.xml\"><file href=\"test.xml\"/>"+dependencies+"</resource>"+resources+"</resources></manifest>");files.put("classroom/document.json",DocumentCodec.write(doc));return zip(files);
    }
    private static BigDecimal qtiPoints(org.w3c.dom.Document doc){
        NodeList declarations=doc.getElementsByTagNameNS("*","outcomeDeclaration");
        for(int i=0;i<declarations.getLength();i++){Element e=(Element)declarations.item(i);if(!"SCORE".equals(e.getAttribute("identifier")))continue;
            try{BigDecimal p=new BigDecimal(e.getAttribute("normalMaximum"));if(p.signum()>0 && p.compareTo(BigDecimal.valueOf(100))<=0)return p;}catch(NumberFormatException ignored){}
        }return BigDecimal.ONE;
    }
    private static String qtiProcessing(ItemNode item,List<String> accepted,boolean choice){
        String condition;
        if(choice)condition="<match><variable identifier=\"RESPONSE\"/><correct identifier=\"RESPONSE\"/></match>";
        else {List<String> checks=accepted.stream().map(v->"<stringMatch caseSensitive=\""+!Boolean.TRUE.equals(item.grading().normalization()!=null?item.grading().normalization().caseInsensitive():false)+"\" substring=\"false\"><variable identifier=\"RESPONSE\"/><baseValue baseType=\"string\">"+esc(v)+"</baseValue></stringMatch>").toList();condition=checks.size()>1?"<or>"+String.join("",checks)+"</or>":checks.isEmpty()?"<baseValue baseType=\"boolean\">false</baseValue>":checks.get(0);}
        return "<responseProcessing><responseCondition><responseIf>"+condition+"<setOutcomeValue identifier=\"SCORE\"><baseValue baseType=\"float\">"+item.points()+"</baseValue></setOutcomeValue></responseIf><responseElse><setOutcomeValue identifier=\"SCORE\"><baseValue baseType=\"float\">0</baseValue></setOutcomeValue></responseElse></responseCondition></responseProcessing>";
    }
    private static void bad(String message){throw new AppException(ErrorCode.BAD_REQUEST,message);}
}
