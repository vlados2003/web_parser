package ipiad.crawler.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.rabbitmq.client.*;
import ipiad.crawler.model.NewsModel;
import ipiad.crawler.model.UrlModel;
import ipiad.crawler.utils.ElasticBridge;
import ipiad.crawler.utils.RequestUtils;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

public class NewsPageParser extends Thread {
    private final ConnectionFactory connectionFactory;
    private static final Logger logger = LoggerFactory.getLogger(NewsPageParser.class);
    private final ElasticBridge elasticBridge;

    public NewsPageParser(ConnectionFactory factory, ElasticBridge bridge) {
        this.connectionFactory = factory;
        this.elasticBridge = bridge;
    }

    @Override
    public void run() {
        try {
            Connection connection = connectionFactory.newConnection();
            Channel channel = connection.createChannel();
            logger.info("Connected to RabbitMQ page queue for publishing");
            while (true) {
                try {
                    if (channel.messageCount(RequestUtils.QUEUE_LINK) == 0) continue;
                    channel.basicConsume(RequestUtils.QUEUE_LINK, false, "pagesTag", new DefaultConsumer(channel) {
                        @Override
                        public void handleDelivery(String consumerTag,
                                                   Envelope envelope,
                                                   AMQP.BasicProperties properties,
                                                   byte[] body)
                                throws IOException {
                            long deliveryTag = envelope.getDeliveryTag();
                            String message = new String(body, StandardCharsets.UTF_8);
                            UrlModel url = new UrlModel();
                            url.objectFromStrJson(message);
                            try {
                                parseAndPutToQueue(url, channel);
                            } catch (InterruptedException e) {
                                logger.info(e.getMessage());
                            }
                            channel.basicAck(deliveryTag, false);
                        }
                    });
                } catch (IndexOutOfBoundsException e) {
                    logger.info(e.getMessage());
                }
            }
        } catch (IOException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    void parseAndPutToQueue(UrlModel url, Channel channel) throws InterruptedException, JsonProcessingException {
        if (elasticBridge.checkExistence(url.getHash())) {
            logger.info("[!] URL: " + url.getUrl() + " was found in ElasticSearch. Hash: " + url.getHash());
            return;
        }
        String urlString = url.getUrl();
        Optional<Document> document = RequestUtils.requestWithRetry(urlString);
        if (document.isPresent()) {
            Document doc = document.get();
            String header = doc.select("h1.publication-title").first().text();
            String summary = doc.select("div.publication-title-yandex").first().text();
            String time = doc.select("time.time2").text();

            int authorIndex = 0;
            String author = "";
            for (Element authorElement : doc.select("span[itemprop=name]")) {
                if (authorIndex == 1) {
                    author = authorElement.text();
                }
                authorIndex++;
            }

            StringBuilder textContent = new StringBuilder();
            Element divElement = doc.select("div.item-text").first();
            for (Element pElement : divElement.select("p")) {
                textContent.append(pElement.text()).append("\n");
            }

            NewsModel news = new NewsModel(
                    header,
                    textContent.toString(),
                    author,
                    summary,
                    urlString,
                    time,
                    url.getHash()
            );
            logger.info(news.toJsonString().toString());
            try {
                channel.basicPublish("", RequestUtils.QUEUE_PAGE, null, news.toJsonString().getBytes());
                logger.info("Published page in the page queue");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

}
