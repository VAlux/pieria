package dev.alvo.pieria.api;

import dev.alvo.pieria.api.controller.HealthController;
import dev.alvo.pieria.api.error.GlobalExceptionHandler;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.health.HealthService;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.model.ModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {HealthController.class, GlobalExceptionHandler.class})
@Import(HealthControllerTests.Wiring.class)
class HealthControllerTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  Wiring wiring;

  @Test
  void upWhenDbOkAndModelReachable() throws Exception {
    wiring.dbOk.set(true);
    wiring.modelReachable.set(true);

    mvc.perform(get("/pieria-health"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status", is("up")))
      .andExpect(jsonPath("$.db", is("ok")))
      .andExpect(jsonPath("$.modelProvider", is("reachable")));
  }

  @Test
  void upWhenDbOkAndModelUnreachable() throws Exception {
    wiring.dbOk.set(true);
    wiring.modelReachable.set(false);

    mvc.perform(get("/pieria-health"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status", is("up")))
      .andExpect(jsonPath("$.db", is("ok")))
      .andExpect(jsonPath("$.modelProvider", is("unreachable")));
  }

  @Test
  void degradedWhenDbDown() throws Exception {
    wiring.dbOk.set(false);
    wiring.modelReachable.set(true);

    mvc.perform(get("/pieria-health"))
      .andExpect(status().isServiceUnavailable())
      .andExpect(jsonPath("$.status", is("degraded")))
      .andExpect(jsonPath("$.db", is("down")));
  }

  @Test
  void responseShapeHasAllRequiredFields() throws Exception {
    wiring.dbOk.set(true);
    wiring.modelReachable.set(false);

    mvc.perform(get("/pieria-health"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status").exists())
      .andExpect(jsonPath("$.db").exists())
      .andExpect(jsonPath("$.modelProvider").exists());
  }

  @TestConfiguration
  static class Wiring {

    final AtomicBoolean dbOk = new AtomicBoolean(true);
    final AtomicBoolean modelReachable = new AtomicBoolean(false);

    /**
     * Mockito-backed {@link DataSource} whose connection validity follows {@code dbOk}: a down DB
     * throws on {@code getConnection()}, mirroring how a real datasource fails the health probe.
     */
    @Bean("healthTestDataSource")
    @Primary
    DataSource dataSource() throws SQLException {
      DataSource dataSource = mock(DataSource.class);
      Connection connection = mock(Connection.class);
      when(connection.isValid(anyInt())).thenReturn(true);
      when(dataSource.getConnection()).thenAnswer(invocation -> {
        if (!dbOk.get()) {
          throw new SQLException("stub db down");
        }
        return connection;
      });
      return dataSource;
    }

    @Bean("healthTestModelGateway")
    @Primary
    ModelGateway modelGateway() {
      return new ModelGateway() {
        @Override
        public String synthesizeRecall(String query, List<RecallCandidate> candidates) {
          return "";
        }

        @Override
        public float[] embed(String text) {
          return new float[0];
        }

        @Override
        public boolean isModelProviderReachable() {
          return modelReachable.get();
        }
      };
    }

    @Bean
    HealthService healthService(DataSource dataSource, ModelGateway modelGateway) {
      return new HealthService(dataSource, modelGateway);
    }
  }
}
