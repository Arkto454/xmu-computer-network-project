package cn.campus.errand.realtime;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TaskNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(TaskNotificationListener.class);
    private final StudentTaskSocketHandler sockets;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(512), runnable -> {
                var thread = new Thread(runnable, "task-notification");
                thread.setDaemon(true);
                return thread;
            });

    public TaskNotificationListener(StudentTaskSocketHandler sockets) { this.sockets = sockets; }

    @TransactionalEventListener
    public void afterCommit(TaskNotice notice) {
        try {
            executor.execute(() -> {
                try {
                    sockets.deliver(notice);
                } catch (RuntimeException failure) {
                    log.warn("Unable to deliver task notification {}", notice.message().eventId());
                }
            });
        } catch (RejectedExecutionException full) {
            log.warn("Task notification queue full for event {}", notice.message().eventId());
        }
    }

    @PreDestroy
    public void stop() { executor.shutdownNow(); }
}
