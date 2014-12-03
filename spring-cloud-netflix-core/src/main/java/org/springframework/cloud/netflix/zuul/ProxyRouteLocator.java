package org.springframework.cloud.netflix.zuul;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.netflix.zuul.ZuulProperties.ZuulRoute;
import org.springframework.context.ApplicationListener;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.util.StringUtils;

/**
 * @author Spencer Gibb
 */
@Slf4j
public class ProxyRouteLocator implements ApplicationListener<EnvironmentChangeEvent> {

	public static final String DEFAULT_ROUTE = "/";

	private DiscoveryClient discovery;

	private ZuulProperties properties;

	private PathMatcher pathMatcher = new AntPathMatcher();

	private AtomicReference<Map<String, ZuulRoute>> routes = new AtomicReference<>();

	private Map<String, String> staticRoutes = new LinkedHashMap<String, String>();

	public ProxyRouteLocator(DiscoveryClient discovery, ZuulProperties properties) {
		this.discovery = discovery;
		this.properties = properties;
	}

	@Override
	public void onApplicationEvent(EnvironmentChangeEvent event) {
		for (String key : event.getKeys()) {
			if (key.startsWith("zuul.routes")) {
				resetRoutes();
				return;
			}
		}
	}
	
	public void addRoute(String path, String location) {
		staticRoutes.put(path, location);
		resetRoutes();
	}

	public Collection<String> getRoutePaths() {
		return getRoutes().keySet();
	}

	public Map<String, String> getRoutes() {

		if (routes.get() == null) {
			routes.set(locateRoutes());
		}

		Map<String, String> values = new LinkedHashMap<String, String>();

		for (String key : routes.get().keySet()) {			
			String url = key;
			values.put(url, routes.get().get(key).getLocation());
		}
		return values;

	}

	public ProxyRouteSpec getMatchingRoute(String path) {
		String location = null;
		String targetPath = null;
		for (Entry<String, ZuulRoute> entry : routes.get().entrySet()) {
			String pattern = entry.getKey();
			if (pathMatcher.match(pattern, path)) {
				ZuulRoute route = entry.getValue();
				String prefix = properties.getPrefix();
				location = route.getLocation();
				targetPath = path;
				if (path.startsWith(prefix) && properties.isStripPrefix()) {
					targetPath = path.substring(prefix.length());
				}
				if (route.isStripPrefix()) {
					int index = route.getPath().indexOf("*");
					index = index > 0 ? index-1 : 0;
					targetPath = path.substring(index);
				}
			}
		}
		return location==null ? null : new ProxyRouteSpec(targetPath, location);
	}
	
	// Package access so ZuulHandlerMapping can reset it's mappings
	void resetRoutes() {
		routes.set(locateRoutes());
	}

	protected LinkedHashMap<String, ZuulRoute> locateRoutes() {

		LinkedHashMap<String, ZuulRoute> routesMap = new LinkedHashMap<>();

		addConfiguredRoutes(routesMap);
		addStaticRoutes(routesMap);

		// Add routes for discovery services by default
		List<String> services = discovery.getServices();
		for (String serviceId : services) {
			// Ignore specifically ignored services and those that were manually
			// configured
			String key = "/" + serviceId + "/**";
			if (!properties.getIgnoredServices().contains(serviceId)
					&& !routesMap.containsKey(key)) {
				routesMap.put(key, new ZuulRoute(key, serviceId));
			}
		}

		if (routesMap.get(DEFAULT_ROUTE) != null) {
			String defaultServiceId = routesMap.get(DEFAULT_ROUTE).getServiceId();
			// Move the defaultServiceId to the end
			routesMap.remove(DEFAULT_ROUTE);
			routesMap.put(DEFAULT_ROUTE, new ZuulRoute(defaultServiceId));
		}

		LinkedHashMap<String, ZuulRoute> values = new LinkedHashMap<>();
		for (Entry<String, ZuulRoute> entry : routesMap.entrySet()) {
			
			String path = entry.getKey();
			// Prepend with slash if not already present.
			if (!path.startsWith("/")) {
				path = "/" + path;
			}

			if (StringUtils.hasText(properties.getPrefix())) {
				path = properties.getPrefix() + path;
				if (!path.startsWith("/")) {
					path = "/" + path;
				}
			}
			
			values.put(path, entry.getValue());

		}

		return values;

	}

	protected void addStaticRoutes(LinkedHashMap<String, ZuulRoute> routes) {
		for (Entry<String, String> entry : staticRoutes.entrySet()) {
			routes.put(entry.getKey(), new ZuulRoute(entry.getKey(), entry.getValue()));			
		}
	}

	protected void addConfiguredRoutes(Map<String, ZuulRoute> routes) {
		Map<String, ZuulRoute> routeEntries = properties.getRoutesWithDefaultServiceIds();
		for (ZuulRoute entry : routeEntries.values()) {
			String route = entry.getPath();
			if (routes.containsKey(route)) {
				log.warn("Overwriting route {}: already defined by {}", route,
						routes.get(route));
			}
			routes.put(route, entry);
		}
	}

	public String getTargetPath(String matchingRoute, String requestURI) {
		String path = getRoutes().get(matchingRoute);
		if (path==null) {
			path = requestURI;
		} else {
			
		}
		return path;
		
	}

	@Data
	@AllArgsConstructor
	public static class ProxyRouteSpec {
		private String path;
		private String location;
	}

}