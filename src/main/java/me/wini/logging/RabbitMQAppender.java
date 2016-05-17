package me.wini.logging;

import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.AppenderLoggingException;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

// note: class name need not match the @Plugin name.
@Plugin(name="RabbitMQAppender", category="Core", elementType="appender", printObject=true)
public class RabbitMQAppender extends AbstractAppender {

	private static final long serialVersionUID = 1L;
	private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private ConnectionFactory factory = new ConnectionFactory();
    private Connection connection = null;
    private Channel channel = null;
    private final String exchange;
    private final String queueNameFormatString;

    protected RabbitMQAppender(String name, String host, int port, String user,
			String password, String virtualHost, String exchange,
			String queueNameFormatString, Filter filter,
			Layout<? extends Serializable> layout, final boolean ignoreExceptions) {
		super(name, filter, layout, ignoreExceptions);
		factory.setHost(host);
		factory.setPort(port);
		factory.setUsername(user);
		factory.setPassword(password);
		factory.setVirtualHost(virtualHost);
		factory.setAutomaticRecoveryEnabled(true);
		
		setExchange(exchange);
		this.exchange = exchange;
		this.queueNameFormatString = queueNameFormatString;
	}
    
    private Channel getChannel() throws IOException, TimeoutException {
    	if(channel == null || !channel.isOpen()) {
    		channel = getConnection().createChannel();
    	}
    	
    	return channel;
    }
    
    private Connection getConnection() throws IOException, TimeoutException {
    	if(connection == null || !connection.isOpen()) {
    		connection = factory.newConnection();
    	}
    	
    	return connection;
    }
    
    private void close() {
    	if(channel != null && channel.isOpen()) {
    		try {
				channel.close();
			} catch (IOException | TimeoutException e) {
				LOGGER.error(e.getMessage());
			}
    	}
    	
    	if(connection != null && connection.isOpen()) {
    		try {
    			connection.close();
    		} catch (IOException e) {
    			LOGGER.error(e.getMessage());
    		}
    	}
    }
    
    private void setExchange(String exchange) {
    	Channel mq;
    	try {
    		mq = getChannel();
    		synchronized (mq) {
				mq.exchangeDeclare(exchange, "topic", true, false, null);
			}
    	} catch (IOException | TimeoutException e) {
    		//close();
    		if (!ignoreExceptions()) {
                throw new AppenderLoggingException(e);
            }
    	}
    }

	// The append method is where the appender does the work.
    // Given a log event, you are free to do with it what you want.
    // This example demonstrates:
    // 1. Concurrency: this method may be called by multiple threads concurrently
    // 2. How to use layouts
    // 3. Error handling
    @Override
    public void append(LogEvent event) {
        readLock.lock();
        try {
            final byte[] bytes = getLayout().toByteArray(event);
            
            String routingKey = String.format(queueNameFormatString, event.getLevel().toString(), 
            						event.getLoggerName());
            
            BasicProperties props = new BasicProperties()
            							.builder()
            							.build();
            
            getChannel().basicPublish(exchange, routingKey, props, bytes);
            
        } catch (Exception ex) {
        	//close();
            if (!ignoreExceptions()) {
                throw new AppenderLoggingException(ex);
            }
        } finally {
            readLock.unlock();
        }
    }
    
    @Override
    public void start() {
        this.setStarting();
        if (getFilter() != null) {
        	getFilter().start();
        }
        this.setStarted();
    }
    
    @Override
    public void stop() {
        this.setStopping();
       if (getFilter() != null) {
           getFilter().stop();
       }
       
       close();
       this.setStopped();
    }

    // Your custom appender needs to declare a factory method
    // annotated with `@PluginFactory`. Log4j will parse the configuration
    // and call this factory method to construct an appender instance with
    // the configured attributes.
    @PluginFactory
    public static RabbitMQAppender createAppender(
            @PluginAttribute("name") String name,
            @PluginAttribute(value="host", defaultString="localhost") String host,
            @PluginAttribute(value="port", defaultInt=5672) int port,
            @PluginAttribute(value="user", defaultString="guest") String user,
            @PluginAttribute(value="password", defaultString="guest") String password,
            @PluginAttribute(value="virtualHost", defaultString="/") String virtualHost,
            @PluginAttribute(value="exchange", defaultString="logging.events") String exchange,
            @PluginAttribute(value="queueNameFormatString", defaultString="%s.%s") String queueNameFormatString,
            @PluginElement("Layout") Layout<? extends Serializable> layout,
            @PluginElement("Filter") final Filter filter,
            @PluginAttribute("otherAttribute") String otherAttribute) {
        if (name == null) {
            LOGGER.error("No name provided for RabbitMQAppender");
            return null;
        }
                
        if (layout == null) {
            layout = PatternLayout.createDefaultLayout();
        }
               
        return new RabbitMQAppender(name, host, port, user, password, virtualHost, exchange, queueNameFormatString,
        		filter, layout, false);
    }
}