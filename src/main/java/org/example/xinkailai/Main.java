package org.example.xinkailai;


// We have imported the necessary tool classes.
// If you need to import additional packages or classes, please import here.

import java.util.*;

public class Main {
    public static void main(String[] args) {
        // please define the JAVA input here. For example: Scanner s = new Scanner(System.in);
        // please finish the function body here.
        // please define the JAVA output here. For example: System.out.println(s.nextInt());
        Scanner sc=new Scanner(System.in);
        String s=sc.next();
        StringBuilder current=new StringBuilder();
        Deque<Integer>numstack=new ArrayDeque<>();
        Deque<String>strstack=new ArrayDeque<>();
        for(int i=0;i<s.length();i++){
            if(Character.isDigit(s.charAt(i))){
                int n=0;
                while(Character.isDigit(s.charAt(i))){
                    n=n*10+s.charAt(i)-'0';
                    i++;
                }
                numstack.addLast(n);
            }
            if(s.charAt(i)=='['){
                strstack.addLast(current.toString());
                current.setLength(0);
            }else if(s.charAt(i)==']'){
                int cot=numstack.removeLast();
                StringBuilder sb=new StringBuilder();
                for(int j=0;j<cot;j++){
                    sb.append(current.toString());
                }
                current=new StringBuilder(strstack.removeLast());
                current.append(sb.toString());
            }else{
                current.append(s.charAt(i));
            }
        }
        System.out.print(current.toString());
    }
}

