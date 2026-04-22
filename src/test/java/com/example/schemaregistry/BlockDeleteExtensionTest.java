package com.example.schemaregistry;

import com.example.schemaregistry.BlockDeleteExtension.DeleteBlockingFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DeleteBlockingFilter}.
 *
 * <p>These tests don't start a real HTTP server. We mock the
 * {@link ContainerRequestContext} and assert that the filter either aborts
 * (for DELETE) or doesn't abort (for everything else). This is fast,
 * reliable, and tests the actual logic without Jetty overhead.
 */
class BlockDeleteExtensionTest {

    private final DeleteBlockingFilter filter = new DeleteBlockingFilter();

    @Test
    void delete_is_blocked_with_405() throws Exception {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getMethod()).thenReturn("DELETE");
        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn("subjects/test");
        when(ctx.getUriInfo()).thenReturn(uri);

        filter.filter(ctx);

        // Capture the Response that was passed to abortWith
        org.mockito.ArgumentCaptor<Response> captor =
            org.mockito.ArgumentCaptor.forClass(Response.class);
        verify(ctx, times(1)).abortWith(captor.capture());

        Response resp = captor.getValue();
        assertEquals(405, resp.getStatus(), "expected 405 Method Not Allowed");
        assertEquals(DeleteBlockingFilter.HEADER_VALUE,
            resp.getHeaderString(DeleteBlockingFilter.HEADER_NAME),
            "expected proof header on the response");
        assertEquals("GET, POST, PUT", resp.getHeaderString("Allow"));
        assertTrue(resp.getEntity().toString().contains("custom-jar-extension"));
    }

    @Test
    void delete_lowercase_also_blocked() throws Exception {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getMethod()).thenReturn("delete");  // lowercase
        when(ctx.getUriInfo()).thenReturn(mock(UriInfo.class));

        filter.filter(ctx);

        verify(ctx, times(1)).abortWith(any(Response.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "HEAD", "OPTIONS"})
    void non_delete_methods_pass_through(String method) throws Exception {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getMethod()).thenReturn(method);

        filter.filter(ctx);

        // abortWith must NOT have been called — request should flow on to SR
        verify(ctx, never()).abortWith(any(Response.class));
    }

    @Test
    void filter_handles_null_uri_info_gracefully() throws Exception {
        // Defensive: some test harnesses deliver requests without URI info.
        // We log using a fallback "?"; filter must not NPE.
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getMethod()).thenReturn("DELETE");
        when(ctx.getUriInfo()).thenReturn(null);

        assertDoesNotThrow(() -> filter.filter(ctx));
        verify(ctx, times(1)).abortWith(any(Response.class));
    }
}
