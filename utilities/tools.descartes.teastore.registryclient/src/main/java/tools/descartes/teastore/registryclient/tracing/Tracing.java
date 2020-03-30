package tools.descartes.teastore.registryclient.tracing;


import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.HttpHeaders;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;

import io.jaegertracing.internal.JaegerTracer;
import io.jaegertracing.internal.propagation.B3TextMapCodec;
import io.jaegertracing.internal.samplers.ConstSampler;

import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.propagation.TextMapExtractAdapter;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;

import zipkin2.reporter.AsyncReporter;
import zipkin2.reporter.okhttp3.OkHttpSender;
import zipkin2.reporter.Sender;

import brave.opentracing.BraveTracer;
import io.jaegertracing.Configuration;
import io.jaegertracing.Configuration.ReporterConfiguration;
import io.jaegertracing.Configuration.SamplerConfiguration;
import io.jaegertracing.Configuration.SenderConfiguration;



/**
 * Utility functions for OpenTracing integration.
 *
 * @author Long Bui
 */
public final class Tracing {
  private Tracing() {
  }

  /**
   * Utility function for loading tracerconfig.
   * @return
   * @return Config
   * @throws IOException df
   */
  static Properties loadConfig() throws IOException {
    String file = "tracer_config.properties";
    InputStream fs = Tracing.class.getClassLoader().getResourceAsStream(file);
    Properties config = new Properties();
    config.load(fs);
    return config;
  }

  /**
   * Test init.
   * @param service is usually the name of the service
   * @return Tracer intended to be used as GlobalTracer
   */
  public static Tracer init(String service) {
    Properties config = null;
    try {
      config = loadConfig();
    } catch (IOException e) {
      e.printStackTrace();
    }
    String tracerName = config.getProperty("tracer");
    Tracer tracer = null;
    if("jaeger".equals(tracerName)) {
      tracer = createJaegerTracer(config.getProperty("jaeger.reporter_host"),
              config.getProperty("jaeger.reporter_port"), service);
    }
    else if ("zipkin".equals(tracerName)) {
     tracer = createZipkinTracer(config.getProperty("zipkin.reporter_host"),
             config.getProperty("zipkin.reporter_port"), service);
    }
    return tracer;
  }

  public static Tracer createJaegerTracer(String arg1, String arg2, String service) {
    SamplerConfiguration sampleConfig = SamplerConfiguration.fromEnv().withType("const").withParam(1);
    SenderConfiguration senderConfig = new SenderConfiguration().withAgentHost(arg1).withAgentPort(Integer.decode(arg2));
    ReporterConfiguration reporterConfig = ReporterConfiguration.fromEnv().withLogSpans(true).withSender(senderConfig);
    Configuration config = new Configuration(service).withSampler(sampleConfig).withReporter(reporterConfig);


    return config.getTracer();
  }

  /**
   * Creates a zipkintracer intended to be used as a GlobalTracer.
   * @param arg1 df
   * @param arg2 df
   * @param service df
   * @return Tracer
   */
  public static Tracer createZipkinTracer(String arg1, String arg2, String service)  {
    Tracer tracer;
    Sender sender = OkHttpSender.create("http://" + arg1 + ":" + arg2 + "/api/v2/spans");
    AsyncReporter<zipkin2.Span> reporter = AsyncReporter.builder(sender).build();
    tracer = BraveTracer.create(brave.Tracing.newBuilder().localServiceName(service).spanReporter(reporter).build());

    return tracer;
  }

  /**
   * This function is used to create an Tracer instance to be used as the
   * GlobalTracer.
   *
   * @param service is usually the name of the service
   * @return Tracer intended to be used as GlobalTracer
   */

  public static io.opentracing.Tracer init2(String service) {
    return new JaegerTracer.Builder(service).withSampler(new ConstSampler(true)).withZipkinSharedRpcSpan()
        .registerInjector(Format.Builtin.HTTP_HEADERS, new B3TextMapCodec.Builder().build())
        .registerExtractor(Format.Builtin.HTTP_HEADERS, new B3TextMapCodec.Builder().build()).build();
  }

  /**
   * This function is used to inject the current span context into the request to
   * be made.
   *
   * @param requestBuilder The requestBuilder object that gets injected
   */
  public static void inject(Invocation.Builder requestBuilder) {
    Span activeSpan = GlobalTracer.get().activeSpan();
    if (activeSpan != null) {
      GlobalTracer.get().inject(activeSpan.context(), Format.Builtin.HTTP_HEADERS,
              Tracing.requestBuilderCarrier(requestBuilder));
    }
  }

  /**
   * Overloaded function used to extract span information out of an
   * HttpServletRequest instance.
   *
   * @param request is the HttpServletRequest isntance with the potential span
   *                informations
   * @return Scope containing the extracted span marked as active. Can be used
   *         with try-with-resource construct
   */
  public static Scope extractCurrentSpan(HttpServletRequest request) {
    Map<String, String> headers = new HashMap<>();
    String path = request.getRequestURL().toString();
    String name = path.substring(path.lastIndexOf('/') +1 );
    if(name.contains("?")) {
      name = name.substring(0,name.indexOf('?'));
    }
    for (String headerName : Collections.list(request.getHeaderNames())) {
      headers.put(headerName, request.getHeader(headerName));
    }
    return buildSpanFromHeaders(headers,name);
  }

  /**
   * Overloaded function used to extract span information out of an HttpHeaders
   * instance.
   *
   * @param httpHeaders is the HttpHeaders instance with the potential span
   *                    informations
   * @return Scope containing the extracted span marked as active. Can be used
   *         with try-with-resource construct
   */
  public static Scope extractCurrentSpan(HttpHeaders httpHeaders) {
    Map<String, String> headers = new HashMap<>();
    for (String headerName : httpHeaders.getRequestHeaders().keySet()) {
      headers.put(headerName, httpHeaders.getRequestHeader(headerName).get(0));
    }
    return buildSpanFromHeaders(headers,"");
  }

  /**
   * Helper method to extract and build the active span out of Map containing the
   * processed headers.
   *
   * @param headers is the Map of the processed headers
   * @return Scope containing the extracted span marked as active. Can be used
   *         with try-with-resource construct
   */
  private static Scope buildSpanFromHeaders(Map<String, String> headers,String name) {
    if(name.equals("")) {
      name = "op";
    }
    io.opentracing.Tracer.SpanBuilder spanBuilder = GlobalTracer.get().buildSpan(name);

    try {
      SpanContext parentSpanCtx = GlobalTracer.get().extract(Format.Builtin.HTTP_HEADERS,
          new TextMapExtractAdapter(headers));
      if (parentSpanCtx != null) {
        spanBuilder = spanBuilder.asChildOf(parentSpanCtx);
      }
    } catch (IllegalArgumentException e) {
      e.printStackTrace();
    }
    return spanBuilder.withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT).startActive(true);
  }

  /**
   * Returns a TextMap Adapter for Invocation.Builder instance.
   *
   * @param builder is the construct where the span information should be injected
   *                to
   * @return the TextMap adapter which can be used for injection
   */
  public static TextMap requestBuilderCarrier(final Invocation.Builder builder) {
    return new TextMap() {
      @Override
      public Iterator<Map.Entry<String, String>> iterator() {
        throw new UnsupportedOperationException("carrier is write-only");
      }

      @Override
      public void put(String key, String value) {
        builder.header(key, value);
      }
    };
  }
}