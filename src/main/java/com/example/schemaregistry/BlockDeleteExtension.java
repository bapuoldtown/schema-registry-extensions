package com.example.schemaregistry;

import io.confluent.kafka.schemaregistry.rest.SchemaRegistryConfig;
import io.confluent.kafka.schemaregistry.rest.extensions.SchemaRegistryResourceExtension;
import io.confluent.kafka.schemaregistry.storage.SchemaRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;
import java.io.IOException;

/**
 * Schema Registry extension that blocks all HTTP DELETE requests
 * with a 405 Method Not Allowed response.
 *
 * <p>Loaded via Confluent's SchemaRegistryResourceExtension SPI. SR reads
 * {@code resource.extension.class} from its config at startup, reflectively
 * instantiates this class, and calls {@link #register} before serving traffic.
 *
 * <p>The inner {@link DeleteBlockingFilter} is a JAX-RS
 * {@link ContainerRequestFilter} — it runs for every incoming request and
 * short-circuits DELETEs via {@code abortWith()} before SR's resource
 * methods (like {@code SubjectsResource.deleteSubject()}) ever execute.
 */
public class BlockDeleteExtension implements SchemaRegistryResourceExtension {

    private static final Logger log = LoggerFactory.getLogger(BlockDeleteExtension.class);

    @Override
    public void register(Configurable<?> config,
                         SchemaRegistryConfig srCfg,
                         SchemaRegistry sr) {
        log.info("+===========================================================+");
        log.info("|  BlockDeleteExtension REGISTERED                          |");
        log.info("|  All HTTP DELETEs will be blocked with 405.               |");
        log.info("+===========================================================+");
        config.register(new DeleteBlockingFilter());
    }

    @Override
    public void close() throws IOException {
        log.info("BlockDeleteExtension closing.");
    }

    /**
     * The actual blocking filter. Package-private so it can be unit-tested.
     */
    @Provider
    public static class DeleteBlockingFilter implements ContainerRequestFilter {

        static final String ERROR_JSON =
            "{\"error_code\":40500,"
          + "\"message\":\"DELETE blocked by custom JAR extension\","
          + "\"blocked_by\":\"custom-jar-extension\"}";

        static final String HEADER_NAME  = "X-Delete-Blocked";
        static final String HEADER_VALUE = "custom-jar-extension";

        @Override
        public void filter(ContainerRequestContext ctx) {
            if (!"DELETE".equalsIgnoreCase(ctx.getMethod())) {
                return;
            }
            log.warn("[BlockDeleteFilter] BLOCKED DELETE {}",
                ctx.getUriInfo() != null ? ctx.getUriInfo().getPath() : "?");

            ctx.abortWith(
                Response.status(Response.Status.METHOD_NOT_ALLOWED)
                    .header("Allow", "GET, POST, PUT")
                    .header(HEADER_NAME, HEADER_VALUE)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(ERROR_JSON)
                    .build()
            );
        }
    }
}
