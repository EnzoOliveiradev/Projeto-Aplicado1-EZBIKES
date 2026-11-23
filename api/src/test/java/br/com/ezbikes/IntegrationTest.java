package br.com.ezbikes;

import br.com.ezbikes.chaos.ChaosState;
import br.com.ezbikes.payment.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base dos testes de integração: PostgreSQL real (mesma versão do compose) com as
 * migrações Flyway aplicadas. Todas as subclasses compartilham o mesmo contexto Spring.
 *
 * <p>Container único para toda a execução: com {@code @Container} ele seria parado ao fim de
 * cada classe de teste, enquanto o contexto Spring em cache continuaria apontando para ele.
 * O Ryuk do Testcontainers remove o container quando a JVM termina.
 */
@SpringBootTest(properties = {"ezbikes.admin.enabled=true", "ezbikes.admin.token=" + IntegrationTest.ADMIN_TOKEN})
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false) // registra o PrometheusMeterRegistry também nos testes
public abstract class IntegrationTest {

    protected static final String ADMIN_TOKEN = "token-de-teste-123";

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    /** Gateway controlado pelos testes; por padrão aprova todas as cobranças. */
    @MockitoBean
    protected PaymentGateway paymentGateway;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ChaosState chaos;

    @BeforeEach
    void resetDatabase() {
        chaos.reset();
        jdbc.update("delete from order_items");
        jdbc.update("delete from orders");
        jdbc.update("update products set stock = 10");
    }

    protected int stockOf(long productId) {
        return jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId);
    }

    protected void setStock(long productId, int stock) {
        jdbc.update("update products set stock = ? where id = ?", stock, productId);
    }

    protected int countOrders() {
        return jdbc.queryForObject("select count(*) from orders", Integer.class);
    }
}
