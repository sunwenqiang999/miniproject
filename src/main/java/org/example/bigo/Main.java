package org.example.bigo;

import java.util.*;


// 注意类名必须为 Main, 不要有任何 package xxx 信息
public class Main {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        // 注意 hasNext 和 hasNextLine 的区别
        String s=in.next();
        String t=in.next();
        Map<Character,Integer>tcount=new HashMap<>();
        Map<Character,Integer>scount=new HashMap<>();
        for(int i=0;i<t.length();i++){
            char temp=t.charAt(i);
            int count=tcount.getOrDefault(temp,0);
            tcount.put(temp,count+1);
        }
        for(int i=0;i<t.length();i++){
            char temp=s.charAt(i);
            int count=scount.getOrDefault(temp,0);
            scount.put(temp,count+1);
        }
        int res=Integer.MAX_VALUE;
        int begin=0;
        int end=t.length()-1;
        if(isvalid(tcount,scount)){
            res=t.length();
        }
        while(begin<s.length()&&end!=(begin+s.length()-1)%s.length()){
            end=(end+1)%s.length();
            char temp=s.charAt(end);
            int count=scount.getOrDefault(temp,0);
            scount.put(temp,count+1);
            while(begin<s.length()&&isvalid(tcount,scount)){
                int len=(end-begin+s.length())%s.length()+1;
                res=Math.min(res,len);
                char p=s.charAt(begin);
                scount.put(p,scount.get(p)-1);
                begin++;
            }
        }
        System.out.print(res==Integer.MAX_VALUE?-1:res);

    }
    public  static boolean isvalid(Map<Character,Integer>tcount,Map<Character,Integer>scount){
        for(Map.Entry<Character,Integer>tt:tcount.entrySet()){
            if(tt.getValue()>scount.getOrDefault(tt.getKey(),0)){
                return false;
            }
        }
        return true;
    }
}