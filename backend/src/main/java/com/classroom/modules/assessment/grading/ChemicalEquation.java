package com.classroom.modules.assessment.grading;

import java.util.*;
import java.util.regex.*;

/** Compare balanced equations by species and proportional coefficients. Formulas and parentheses are parsed, never executed. */
public final class ChemicalEquation {
    private ChemicalEquation() {}
    private record Equation(Map<String,Long> left,Map<String,Long> right) {}
    public static boolean equivalent(String a,String b){try{Equation x=parse(a),y=parse(b);if(!x.left.keySet().equals(y.left.keySet()) || !x.right.keySet().equals(y.right.keySet()))return false;
        long[] ratio=new long[2];for(var side:List.of(Map.entry(x.left,y.left),Map.entry(x.right,y.right)))for(var e:side.getKey().entrySet()){long v=side.getValue().get(e.getKey());if(ratio[0]==0){ratio[0]=e.getValue();ratio[1]=v;}else if(Math.multiplyExact(e.getValue(),ratio[1])!=Math.multiplyExact(v,ratio[0]))return false;}return true;
    }catch(RuntimeException e){return false;}}
    public static void validate(String text){parse(text);}
    private static Equation parse(String text){if(text==null || text.length()>2000)throw new IllegalArgumentException("Length");String[] sides=text.replace("→","=").replace("->","=").replaceAll("\\s+","").split("=",-1);if(sides.length!=2)throw new IllegalArgumentException("Equation");Map<String,Long> left=side(sides[0]),right=side(sides[1]);if(!atoms(left).equals(atoms(right)))throw new IllegalArgumentException("Unbalanced equation");return new Equation(left,right);}
    private static Map<String,Long> side(String text){Map<String,Long> out=new TreeMap<>();for(String part:text.split("\\+",-1)){Matcher m=Pattern.compile("(\\d{1,4})?([A-Z][A-Za-z0-9()]{0,100})").matcher(part);if(!m.matches())throw new IllegalArgumentException("Species");long n=m.group(1)==null?1:Long.parseLong(m.group(1));if(n<1)throw new IllegalArgumentException("Coefficient");formula(m.group(2));out.merge(m.group(2),n,Math::addExact);}if(out.size()>30)throw new IllegalArgumentException("Species limit");return out;}
    private static Map<String,Long> atoms(Map<String,Long> side){Map<String,Long> out=new TreeMap<>();side.forEach((s,n)->formula(s).forEach((element,count)->out.merge(element,Math.multiplyExact(n,count),Math::addExact)));return out;}
    private static Map<String,Long> formula(String text){FormulaParser p=new FormulaParser(text);Map<String,Long> r=p.group(0);if(p.pos!=text.length())throw new IllegalArgumentException("Formula");return r;}
    private static final class FormulaParser {
        final String text;int pos;FormulaParser(String text){this.text=text;}
        Map<String,Long> group(int depth){if(depth>8)throw new IllegalArgumentException("Nesting");Map<String,Long> out=new TreeMap<>();while(pos<text.length() && text.charAt(pos)!=')'){Map<String,Long> part;
            if(text.charAt(pos)=='('){pos++;part=group(depth+1);if(pos==text.length() || text.charAt(pos++)!=')')throw new IllegalArgumentException("Bracket");}
            else{int start=pos++;if(!Character.isUpperCase(text.charAt(start)))throw new IllegalArgumentException("Element");if(pos<text.length() && Character.isLowerCase(text.charAt(pos)))pos++;part=Map.of(text.substring(start,pos),1L);}
            int start=pos;while(pos<text.length() && Character.isDigit(text.charAt(pos)))pos++;long n=start==pos?1:Long.parseLong(text.substring(start,pos));if(n<1 || n>10000)throw new IllegalArgumentException("Subscript");part.forEach((e,c)->out.merge(e,Math.multiplyExact(c,n),Math::addExact));
        }if(out.isEmpty())throw new IllegalArgumentException("Empty formula");return out;}
    }
}
