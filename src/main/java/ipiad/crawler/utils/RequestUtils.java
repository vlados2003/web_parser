package ipiad.crawler.utils;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

public class RequestUtils {
    private static final int CONNECTION_TIMEOUT = 5000;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    public static final String QUEUE_LINK = "crawler_link";
    public static final String QUEUE_PAGE = "crawler_news";
    private static final Logger logger = LoggerFactory.getLogger(RequestUtils.class);

    public static Optional<Document> requestWithRetry(String url) {
        Optional<Document> document = Optional.empty();
        for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                final HttpGet httpGet = new HttpGet(url);
                try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                    int statusCode = response.getStatusLine().getStatusCode();
                    switch (statusCode) {
                        case 200: {
                            HttpEntity entity = response.getEntity();
                            if (entity != null) {
                                try {
                                    document = Optional.ofNullable(Jsoup.parse(entity.getContent(), "UTF-8", url));
                                    logger.info("[*] Thread ID: " + Thread.currentThread().getId() +
                                            " - Received webpage from: " + url);
                                    return document;
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                            break;
                        }
                        case 403:
                        case 429: {
                            logger.info("[*] Thread ID: " + Thread.currentThread().getId() +
                                    " - Error at " + url + " with status code " + statusCode);
                            int delay = CONNECTION_TIMEOUT * (attempt + 1);
                            try {
                                response.close();
                                httpClient.close();
                                logger.info("[!] Waiting for " + delay / 1000 + " seconds");
                                Thread.sleep(delay);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                                break;
                            }
                            break;
                        }
                        case 404:
                            logger.info("[*] Thread ID: " + Thread.currentThread().getId() + " - Received 404 for " + url);
                            break;
                        default:
                            break;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return document;
    }
}