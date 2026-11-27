package vn.nhom17.banhang.platform.internal.write;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnExpression("${app.backoffice.queue.consume-enabled:false} or ${app.backoffice.queue.file-consume-enabled:false}")
class BackofficeResultPruner {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    BackofficeResultPruner(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
    }
    @Scheduled(fixedDelayString = "${app.backoffice.queue.result-prune-interval-ms:60000}", initialDelay = 60000)
    void prune() {
        tx.executeWithoutResult(transaction -> jdbc.queryForObject("SELECT app.prune_backoffice_write_results()", Integer.class));
    }
}
