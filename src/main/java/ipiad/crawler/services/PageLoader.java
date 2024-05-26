package ipiad.crawler.services;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import ipiad.crawler.model.NewsModel;
import ipiad.crawler.utils.ElasticBridge;
import ipiad.crawler.utils.RequestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static java.nio.charset.StandardCharsets.UTF_8;

public class PageLoader extends Thread {
    private final ConnectionFactory rmqFactory;
    private final ElasticBridge elasticBridge;
    private static final Logger logger = LoggerFactory.getLogger(PageLoader.class);

    public PageLoader(ConnectionFactory factory, ElasticBridge elasticBridge) {
        this.rmqFactory = factory;
        this.elasticBridge = elasticBridge;
    }

    @Override
    public void run() {
        try {
            Connection connection = rmqFactory.newConnection();
            Channel channel = connection.createChannel();
            logger.info("PageLoader connected to RabbitMQ");
            while (true) {
                if (channel.messageCount(RequestUtils.QUEUE_PAGE) == 0) {
                    continue;
                }
                String newsJson = new String(channel.basicGet(RequestUtils.QUEUE_PAGE, true).getBody(), UTF_8);
                NewsModel newsModel = new NewsModel();
                logger.info("Got parsed data to insert" + newsJson);
                newsModel.objectFromStrJson(newsJson);
                if (!elasticBridge.checkExistence(newsModel.getHash())) {
                    elasticBridge.insertData(newsModel);
                    logger.info("Inserted data from " + newsModel.getURL() + " into Elastic");
                } else {
                    logger.info("[!] URL: " + newsModel.getURL() + " was found in Elastic. Hash: " + newsModel.getHash());
                }
            }
        } catch (IOException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }
}
