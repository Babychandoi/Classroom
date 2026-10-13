package com.classroom.modules.assessment.grading;

import java.math.BigDecimal;
import java.util.*;

/** Bounded arithmetic parser. No scripting/reflection/function calls; exact polynomial comparison uses decimal coefficients. */
public final class SafeExpression {
    private SafeExpression() {}
    public static boolean equivalent(String left,String right) {
        try {Rational a=new Parser(left).parse(),b=new Parser(right).parse();
            // Require matching denominator domains. Teachers may use expanded/factored polynomials and numeric fractions.
            return a.num.multiply(b.den).same(b.num.multiply(a.den)) && a.den.proportional(b.den);
        }catch(IllegalArgumentException e){return false;}
    }
    public static double evaluate(String expression,Map<String,Double> variables) {
        Rational value=new Parser(expression).parse();double den=value.den.evaluate(variables);
        if(Math.abs(den)<1e-12)throw new IllegalArgumentException("Division by zero");
        double result=value.num.evaluate(variables)/den;if(!Double.isFinite(result))throw new IllegalArgumentException("Non-finite result");return result;
    }
    public static void validate(String expression){new Parser(expression).parse();}
    private record Rational(Polynomial num,Polynomial den) {
        Rational add(Rational b){return new Rational(num.multiply(b.den).add(b.num.multiply(den)),den.multiply(b.den));}
        Rational negate(){return new Rational(num.scale(BigDecimal.valueOf(-1)),den);}
        Rational multiply(Rational b){return new Rational(num.multiply(b.num),den.multiply(b.den));}
        Rational divide(Rational b){if(b.num.zero())throw new IllegalArgumentException("Zero denominator");return new Rational(num.multiply(b.den),den.multiply(b.num));}
        Rational power(int n){if(n<0)return new Rational(den,num).power(-n);Rational r=constant(BigDecimal.ONE);for(int i=0;i<n;i++)r=r.multiply(this);return r;}
    }
    private static Rational constant(BigDecimal n){return new Rational(Polynomial.constant(n),Polynomial.constant(BigDecimal.ONE));}
    private static final class Polynomial {
        final SortedMap<String,BigDecimal> terms;
        Polynomial(Map<String,BigDecimal> values){terms=new TreeMap<>();values.forEach((k,v)->{if(v.precision()>1000 || Math.abs(v.scale())>1000)throw new IllegalArgumentException("Coefficient limit");if(v.signum()!=0)terms.put(k,v.stripTrailingZeros());});if(terms.size()>300)throw new IllegalArgumentException("Too many terms");}
        static Polynomial constant(BigDecimal n){return new Polynomial(Map.of("",n));}
        boolean zero(){return terms.isEmpty();}
        Polynomial add(Polynomial b){Map<String,BigDecimal> r=new TreeMap<>(terms);b.terms.forEach((k,v)->r.merge(k,v,BigDecimal::add));return new Polynomial(r);}
        Polynomial scale(BigDecimal scale){Map<String,BigDecimal> r=new TreeMap<>();terms.forEach((k,v)->r.put(k,v.multiply(scale)));return new Polynomial(r);}
        Polynomial multiply(Polynomial b){Map<String,BigDecimal> r=new TreeMap<>();if((long)terms.size()*b.terms.size()>10000)throw new IllegalArgumentException("Expression too complex");
            terms.forEach((ka,va)->b.terms.forEach((kb,vb)->r.merge(monomial(ka,kb),va.multiply(vb),BigDecimal::add)));return new Polynomial(r);}
        boolean same(Polynomial b){if(!terms.keySet().equals(b.terms.keySet()))return false;return terms.entrySet().stream().allMatch(e->e.getValue().compareTo(b.terms.get(e.getKey()))==0);}
        boolean proportional(Polynomial b){if(!terms.keySet().equals(b.terms.keySet()) || zero())return false;String first=terms.firstKey();return scale(b.terms.get(first)).same(b.scale(terms.get(first)));}
        double evaluate(Map<String,Double> variables){double result=0;for(var term:terms.entrySet()){double v=term.getValue().doubleValue();for(var p:powers(term.getKey()).entrySet()){Double x=variables.get(p.getKey());if(x==null || !Double.isFinite(x))throw new IllegalArgumentException("Unknown variable");v*=Math.pow(x,p.getValue());}result+=v;}return result;}
        static Map<String,Integer> powers(String key){Map<String,Integer> r=new TreeMap<>();if(!key.isEmpty())for(String s:key.split("\\*")){String[] p=s.split("\\^");r.put(p[0],Integer.parseInt(p[1]));}return r;}
        static String monomial(String a,String b){Map<String,Integer> r=powers(a);powers(b).forEach((k,v)->r.merge(k,v,Integer::sum));if(r.values().stream().mapToInt(Integer::intValue).sum()>16 || r.size()>12)throw new IllegalArgumentException("Degree limit");return String.join("*",r.entrySet().stream().map(e->e.getKey()+"^"+e.getValue()).toList());}
    }
    private static final class Parser {
        final String text;int pos,depth;
        Parser(String text){if(text==null || text.length()>2000)throw new IllegalArgumentException("Expression length");this.text=text.replace('−','-').replace('×','*').replace('÷','/').replace("**","^");}
        Rational parse(){Rational r=sum();space();if(pos!=text.length())throw new IllegalArgumentException("Unexpected input");return r;}
        Rational sum(){Rational r=product();while(true){if(take('+'))r=r.add(product());else if(take('-'))r=r.add(product().negate());else return r;}}
        Rational product(){Rational r=unary();while(true){if(take('*'))r=r.multiply(unary());else if(take('/'))r=r.divide(unary());else return r;}}
        Rational unary(){if(++depth>40)throw new IllegalArgumentException("Nesting limit");try{if(take('+'))return unary();if(take('-'))return unary().negate();Rational r=atom();if(take('^')){space();int start=pos;if(pos<text.length() && text.charAt(pos)=='-')pos++;while(pos<text.length() && Character.isDigit(text.charAt(pos)))pos++;int n=Integer.parseInt(text.substring(start,pos));if(Math.abs(n)>8)throw new IllegalArgumentException("Power limit");r=r.power(n);}return r;}finally{depth--;}}
        Rational atom(){if(take('(')){Rational r=sum();if(!take(')'))throw new IllegalArgumentException("Missing bracket");return r;}space();int start=pos;
            if(pos<text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos)=='.')){while(pos<text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos)=='.'))pos++;String n=text.substring(start,pos);if(n.length()>40)throw new IllegalArgumentException("Number limit");return constant(new BigDecimal(n));}
            if(pos<text.length() && Character.isLetter(text.charAt(pos))){while(pos<text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos)=='_'))pos++;String name=text.substring(start,pos);if(!name.matches("[A-Za-z][A-Za-z0-9_]{0,15}"))throw new IllegalArgumentException("Variable name");return new Rational(new Polynomial(Map.of(name+"^1",BigDecimal.ONE)),Polynomial.constant(BigDecimal.ONE));}
            throw new IllegalArgumentException("Expected arithmetic");}
        void space(){while(pos<text.length() && Character.isWhitespace(text.charAt(pos)))pos++;}
        boolean take(char c){space();if(pos<text.length() && text.charAt(pos)==c){pos++;return true;}return false;}
    }
}
