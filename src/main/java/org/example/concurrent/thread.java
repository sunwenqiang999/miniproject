package org.example.concurrent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 使用 submit 提交 100 个 HTTP 请求任务，并收集全部 String 返回值。
 */
public class thread {

    public static void main(String[] args) {
        int requestCount = 100;
        int concurrency = 20;
        String url = "https://httpbin.org/get";

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        try {
            List<Future<String>> futures = new ArrayList<>(requestCount);

            // 第一阶段：循环提交任务。submit 返回 Future，不等待 HTTP 请求完成。
            for (int index = 0; index < requestCount; index++) {
                final int requestIndex = index + 1;

                Future<String> future = executor.submit(() -> {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();

                    try {
                        HttpResponse<String> response = client.send(
                                request,
                                HttpResponse.BodyHandlers.ofString()
                        );

                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            return response.body();
                        }
                        return "第 " + requestIndex + " 次请求失败，HTTP 状态码：" + response.statusCode();
                    } catch (IOException exception) {
                        return "第 " + requestIndex + " 次请求异常：" + exception.getMessage();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return "第 " + requestIndex + " 次请求被中断";
                    }
                });

                futures.add(future);
            }

            // 第二阶段：全部任务已提交，再依次获取每个 Future 中的 String 结果。
            List<String> results = new ArrayList<>(requestCount);
            for (Future<String> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    results.add("等待请求结果时线程被中断");
                    break;
                } catch (ExecutionException exception) {
                    results.add("请求任务执行异常：" + exception.getCause().getMessage());
                }
            }

            System.out.println("请求完成，总结果数：" + results.size());
            for (int index = 0; index < results.size(); index++) {
                System.out.printf("第 %d 次请求结果：%s%n", index + 1, results.get(index));
            }
        } finally {
            executor.shutdown();
        }
    }
}
