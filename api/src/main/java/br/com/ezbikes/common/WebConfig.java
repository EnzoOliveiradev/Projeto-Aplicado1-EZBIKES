package br.com.ezbikes.common;

import br.com.ezbikes.admin.AdminProperties;
import br.com.ezbikes.admin.AdminTokenInterceptor;
import br.com.ezbikes.chaos.ChaosInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ChaosInterceptor chaos;
    private final AdminProperties admin;

    public WebConfig(ChaosInterceptor chaos, AdminProperties admin) {
        this.chaos = chaos;
        this.admin = admin;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(chaos).addPathPatterns("/api/**");
        if (admin.enabled()) {
            registry.addInterceptor(new AdminTokenInterceptor(admin.token())).addPathPatterns("/admin/**");
        }
    }
}
