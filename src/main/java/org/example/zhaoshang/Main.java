package org.example.zhaoshang;
import java.util.*;

class Main
{
    /**
     * 代码中的类名、方法名、参数名已经指定，请勿修改，直接返回方法规定的值即可
     * 计算利率表达式的结果
     * @param expression string字符串 包含百分数的数学表达式字符串，例如"5%+10%*2"
     * @return double浮点型
     */
    public static double calculateInterest(String expression)
    {
        // write code here
        String exp=expression.replace("%","/100");
        double res=cal(exp);
        return res*100;
    }
    public static int priority(char op){
        if(op=='+'||op=='-'){
            return 1;
        }
        if(op=='*'||op=='/'){
            return 2;
        }
        return 0;
    }
    public static double calu(double a,double b,char op){
        //System.out.println(a);
        //System.out.println(b);

        switch(op){
            case '+' : return a+b;
            case '-' : return b-a;
            case '*' : return a*b;
            case '/' : return b/a;
        }
        return 0;
    }
    public static double cal(String s){
        Deque<Double>num=new ArrayDeque<>();
        Deque<Character>fuhao=new ArrayDeque<>();
        for(int i=0;i<s.length();i++)
        {
            if(Character.isDigit(s.charAt(i))){
                double sum=0;
                while(i<s.length()&&Character.isDigit(s.charAt(i))){
                    sum=sum*10+s.charAt(i)-'0';
                    i++;
                }
                if(i<s.length()&&s.charAt(i)=='.'){
                    i++;
                    double p=10;
                    while(i<s.length()&&Character.isDigit(s.charAt(i))){
                        sum=sum+(s.charAt(i)-'0')/p;
                        i++;
                        p*=10;
                    }
                }
                //System.out.println(sum);
                num.addLast(sum);
                i--;
            }else if(s.charAt(i)=='('){
                fuhao.addLast(s.charAt(i));
            }else if(s.charAt(i)==')'){
                while(fuhao.getLast()!='('){
                    double a=num.removeLast();
                    double b=num.removeLast();
                    char op = fuhao.removeLast();
                    double ans=calu(a,b,op);
                    num.addLast(ans);
                }
                fuhao.removeLast();
            }else{
                while(!fuhao.isEmpty()&&priority(s.charAt(i))<=priority(fuhao.getLast())){
                    double a=num.removeLast();
                    double b=num.removeLast();
                    char op = fuhao.removeLast();
                    double ans=calu(a,b,op);
                    num.addLast(ans);
                }
                fuhao.addLast(s.charAt(i));
            }
        }
        while(!fuhao.isEmpty()){
            double a=num.removeLast();
            double b=num.removeLast();
            char op = fuhao.removeLast();
            double ans=calu(a,b,op);
            num.addLast(ans);
        }
        return num.getLast();
    }
}

