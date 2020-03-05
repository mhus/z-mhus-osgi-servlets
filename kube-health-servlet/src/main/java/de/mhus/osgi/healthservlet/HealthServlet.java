/**
 * Copyright 2018 Mike Hummel
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.mhus.osgi.healthservlet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;
import java.util.logging.Logger;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.felix.hc.api.Result.Status;
import org.apache.felix.hc.api.execution.HealthCheckExecutor;
import org.apache.karaf.log.core.LogService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@Component(
        service = Servlet.class,
        property = "alias=/system/health/*",
        name = "HealthServlet",
        servicefactory = true,
        configurationPolicy = ConfigurationPolicy.OPTIONAL
        )
@Designate(ocd = HealthServlet.Config.class)
public class HealthServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private ComponentContext ctx;
    private long startChecking;
    private LogServiceTracker tracker;
    private static Logger log = Logger.getLogger(HealthServlet.class.getCanonicalName());

    private HealthCheckExecutor healthCheckExecutor;
    private ConfigTemplate config;

    @ObjectClassDefinition(name = "Health Check Servlet", description = "For Kubernetes")
    public @interface Config {
        @AttributeDefinition(name = "Wait After Start", description = "ms before activation")
        long waitAfterStart() default 60000;
        @AttributeDefinition(name = "Bundles Ignore", description = "List of Bundles to ignore (separate by comma)")
        String[] bundlesIgnore() default {
            "org.apache.karaf.features.extension",
            "org.apache.aries.blueprint.core.compatibility",
            "org.apache.karaf.shell.console,org.jline.terminal-jansi"
        };
        @AttributeDefinition(name = "Enable Bundle Check", description = "Validate if all bundles are active")
        boolean bundlesEnabled() default true;
        @AttributeDefinition(name = "Enable Log Check", description = "Introspect the own logs and alert for patterns")
        boolean logEnabled() default true;
        @AttributeDefinition(name = "Log Level To Check", description = "5=Debug")
        int logLevel() default 5;
        @AttributeDefinition(name = "Log Patterns", description = "List of patterns to watch for")
        String[] logPatterns() default {};
        boolean logResetFinding() default false;
        boolean checkEnabled() default true;
        String[] checkIgnore() default {};
        boolean checkCombineTagsWithOr() default false;
        boolean checkForceInstantExecution() default false;
        String checkOverrideGlobalTimeoutStr() default "";
        String checkTags() default "*";
    }
    
    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    public void setHealthCheckExecutor(HealthCheckExecutor healthCheckExecutor) {
        log.info("Found healthCheckExecutor");
        this.healthCheckExecutor = healthCheckExecutor;
    }
    
    @Activate
    public void activate(ComponentContext ctx, Config c) {
        this.ctx = ctx;
        this.config = new ConfigTemplate(c);

        startChecking = System.currentTimeMillis() + config.waitAfterStart ;

        if (config.logEnabled) {
            tracker = new LogServiceTracker(ctx.getBundleContext(), LogService.class, null, config);
            tracker.open();
        }
    }

    @Deactivate
    public void deactivate(ComponentContext ctx) {
        if (tracker != null)
            tracker.close();
        tracker = null;
        this.ctx = null;
        ctx.getProperties().put("waitAfterStart", 100);
    }
    
    @Modified
    public void modified(ComponentContext ctx, Config c) {
        this.config = new ConfigTemplate(c);
        if (config.logEnabled && tracker == null) {
            tracker = new LogServiceTracker(ctx.getBundleContext(), LogService.class, null, config);
            tracker.open();
        } else
        if (!config.logEnabled && tracker != null) {
            tracker.close();
            tracker = null;
        }
    }

    public HealthServlet() {}

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        if (System.currentTimeMillis() < startChecking ) {
            res.setContentType("text/plain");
            
            // disable wait if all bundles are active
            boolean healthy = true;
            if (config.bundlesEnabled) {
                if (!HealthCheckUtil.checkBundles(ctx, config, null))
                    healthy = false;
                
                if (healthy)
                    startChecking = 0;
            } else
                healthy = false;

            if (!healthy) {
                PrintWriter out = res.getWriter();
                long time = System.currentTimeMillis();
                out.println("time: " + time + " " + new Date(time));
                out.println("wait: Wait after start");
                out.println("status: ok");
                out.flush();
                out.close();
                return;
            }
        }
        
        boolean healthy = true;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter out = new PrintWriter(baos);
        long time = System.currentTimeMillis();
        out.println("time: " + time + " " + new Date(time));
        // check if bundles are ok
        if (config.bundlesEnabled) {
            if (!HealthCheckUtil.checkBundles(ctx, config, out))
                healthy = false;
        }
        
        // check log
        if (config.logEnabled && tracker.logFindings.size() > 0) {
            healthy = false;
            for (String finding : tracker.logFindings)
                out.println("Log: " + finding);
            if (config.logResetFinding)
                tracker.logFindings.clear();
        }

        // check felix health check
        if (config.checkEnabled) {
            HealthCheckUtil.checkServices(healthCheckExecutor, config, out, log, Status.CRITICAL, Status.HEALTH_CHECK_ERROR);
        }
        
        if (!healthy) {
            res.setStatus(501);
            out.println("status: error");
        } else {
            res.setStatus(200);
            out.println("status: ok");
        }
        
        res.setContentType("text/plain");

        out.flush();
        out.close();
        String content = new String(baos.toByteArray());
        res.getWriter().print(content);
        res.getWriter().flush();
        res.getWriter().close();
        if (!healthy) {
            log.severe("Health check failed:\n" + content);
        }
    }


}
