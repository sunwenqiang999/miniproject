package org.example.datastructure;

public class TriTree {
    public static class TriNode {
        TriNode[] node;
        boolean isEnd;
        public TriNode() {
            this.node = new TriNode[26];
            this.isEnd = false;
        }
    }
    public static void insert(TriNode root, String word,int index){
        if(index>=word.length()){
            root.isEnd=true;
            return;
        }
        int pos = word.charAt(index)-'a';
        if(root.node[pos]==null){
            root.node[pos] = new TriNode();
            insert(root.node[pos],word,index+1);
        }else {
            insert(root.node[pos], word, index + 1);
        }
    }
    public boolean search(TriNode root,String word,int index){
        // 终止条件1：所有字符匹配完毕 index走到末尾
        if(index == word.length()){
            // 必须isEnd=true才是完整单词，只是前缀不算
            return root.isEnd;
        }
        int pos = word.charAt(index) - 'a';
        // 当前字母子节点不存在→单词不存在
        if(root.node[pos]==null){
            return false;
        }
        // 递归去下一个字符
        return search(root.node[pos], word, index+1);
    }

    // DFS深度遍历，sb拼接路径字符
    public static void dfsPrint(TriNode cur, StringBuilder sb) {
        // 当前节点是单词结尾，输出
        if (cur.isEnd) {
            System.out.println(sb);
        }
        // 遍历26个字母分支
        for (int i = 0; i < 26; i++) {
            if (cur.node[i] != null) {
                // 入：加上当前字母
                sb.append((char) ('a' + i));
                dfsPrint(cur.node[i], sb);
                // 回溯：删掉最后一个字符（关键）
                sb.deleteCharAt(sb.length() - 1);
            }
        }
    }

    public static void main(String[] args) {
        TriNode root = new TriNode();

        // 插入测试单词
        insert(root, "app",0);
        insert(root, "apple",0);
        insert(root, "banana",0);
        insert(root, "bat",0);

        System.out.println("Trie中所有单词：");
        dfsPrint(root, new StringBuilder());
    }
}
