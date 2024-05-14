/*
 * Copyright 2008-2019 by Emeric Vernat
 *
 *     This file is part of Java Melody.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.bull.javamelody.internal.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import net.bull.javamelody.Parameter;
import net.bull.javamelody.internal.model.TransportFormat;

/**
 * Classe d'accès aux paramètres du monitoring.
 * @author Emeric Vernat
 */
public final class Parameters {
	public static final String PARAMETER_SYSTEM_PREFIX = "javamelody.";
	public static final File TEMPORARY_DIRECTORY = new File(System.getProperty("java.io.tmpdir"));
	public static final String JAVA_VERSION = System.getProperty("java.version");
	public static final String JAVAMELODY_VERSION = getJavaMelodyVersion();
	// default monitoring-path is "/monitoring" in the http URL
	private static final String DEFAULT_MONITORING_PATH = "/monitoring";
	// résolution (ou pas) par défaut en s de stockage des valeurs dans les fichiers RRD
	private static final int DEFAULT_RESOLUTION_SECONDS = 60;
	// stockage des fichiers RRD de JRobin dans le répertoire temp/javamelody/<context> par défaut
	private static final String DEFAULT_DIRECTORY = "javamelody";
	// nom du fichier stockant les applications et leurs urls dans le répertoire de stockage
	private static final String COLLECTOR_APPLICATIONS_FILENAME = "applications.properties";
	private static final boolean PDF_ENABLED = computePdfEnabled();
	private static Map<String, List<URL>> urlsByApplications;
	private static Map<String, List<String>> applicationsByAggregationApplications;

	private static FilterConfig filterConfig;
	private static ServletContext servletContext;
	private static String lastConnectUrl;
	private static Properties lastConnectInfo;
	private static boolean dnsLookupsDisabled;

	private Parameters() {
		super();
	}

	public static void initialize(FilterConfig config) {
		filterConfig = config;
		if (config != null) {
			final ServletContext context = config.getServletContext();
			initialize(context);
		}
	}

	public static void initialize(ServletContext context) {
		servletContext = context;

		dnsLookupsDisabled = Parameter.DNS_LOOKUPS_DISABLED.getValueAsBoolean();
	}

	public static void initJdbcDriverParameters(String connectUrl, Properties connectInfo) {
		lastConnectUrl = connectUrl;
		lastConnectInfo = connectInfo;
	}

	/**
	 * @return Contexte de servlet de la webapp, soit celle monitorée ou soit celle de collecte.
	 */
	public static ServletContext getServletContext() {
		assert servletContext != null;
		return servletContext;
	}

	public static String getLastConnectUrl() {
		return lastConnectUrl;
	}

	public static Properties getLastConnectInfo() {
		return lastConnectInfo;
	}

	/**
	 * @return Nom et urls des applications telles que paramétrées dans un serveur de collecte.
	 * @throws IOException e
	 */
	public static Map<String, List<URL>> getCollectorUrlsByApplications() throws IOException {
		if (urlsByApplications == null) {
			readCollectorApplications();
		}
		return Collections.unmodifiableMap(urlsByApplications);
	}

	public static Map<String, List<String>> getApplicationsByAggregationApplication()
			throws IOException {
		if (applicationsByAggregationApplications == null) {
			readCollectorApplications();
		}
		return Collections.unmodifiableMap(applicationsByAggregationApplications);
	}

	public static void addCollectorApplication(String application, List<URL> urls)
			throws IOException {
		assert application != null;
		assert urls != null && !urls.isEmpty();
		// initialisation si besoin
		getCollectorUrlsByApplications();

		urlsByApplications.put(application, urls);
		writeCollectorApplications();
	}

	public static void addCollectorAggregationApplication(String aggregationApplication,
			List<String> aggregatedApplications) throws IOException {
		assert aggregationApplication != null;
		assert aggregatedApplications != null && !aggregatedApplications.isEmpty();
		// initialisation si besoin
		getCollectorUrlsByApplications();

		applicationsByAggregationApplications.put(aggregationApplication, aggregatedApplications);
		writeCollectorApplications();
	}

	public static void removeCollectorApplication(String application) throws IOException {
		assert application != null;
		// initialisation si besoin
		getCollectorUrlsByApplications();

		if (urlsByApplications.containsKey(application)) {
			urlsByApplications.remove(application);
		} else {
			applicationsByAggregationApplications.remove(application);
		}
		synchronizeAggregationApplications();
		writeCollectorApplications();
	}

	private static void writeCollectorApplications() throws IOException {
		final Properties properties = new Properties();
		final String dummyUrl = "http://localhost";
		final String urlSuffix = parseUrls(dummyUrl).get(0).toString().substring(dummyUrl.length());
		for (final Map.Entry<String, List<URL>> entry : urlsByApplications.entrySet()) {
			final List<URL> urls = entry.getValue();
			assert urls != null && !urls.isEmpty();
			final StringBuilder sb = new StringBuilder();
			for (final URL url : urls) {
				final String urlString = url.toString();
				// on enlève le suffixe ajouté précédemment dans parseUrls
				String webappUrl = urlString.substring(0, urlString.lastIndexOf(urlSuffix));
				if (webappUrl.length() + urlSuffix.length() < urlString.length()) {
					// on remet l'éventuel queryString comme avant parseUrls
					webappUrl += "?"
							+ urlString.substring(webappUrl.length() + urlSuffix.length() + 1);
				}
				if (webappUrl.indexOf(',') != -1) {
					throw new IOException("The URL should not contain a comma.");
				}
				sb.append(webappUrl).append(',');
			}
			sb.delete(sb.length() - 1, sb.length());
			properties.put(entry.getKey(), sb.toString());
		}
		for (final Map.Entry<String, List<String>> entry : applicationsByAggregationApplications
				.entrySet()) {
			final List<String> applications = entry.getValue();
			final StringBuilder sb = new StringBuilder();
			for (final String application : applications) {
				if (application.indexOf(',') != -1) {
					throw new IOException("The application name should not contain a comma.");
				}
				sb.append(application).append(',');
			}
			sb.delete(sb.length() - 1, sb.length());
			properties.put(entry.getKey(), sb.toString());
		}
		final File collectorApplicationsFile = getCollectorApplicationsFile();
		final File directory = collectorApplicationsFile.getParentFile();
		if (!directory.mkdirs() && !directory.exists()) {
			throw new IOException("JavaMelody directory can't be created: " + directory.getPath());
		}
		try (FileOutputStream output = new FileOutputStream(collectorApplicationsFile)) {
			properties.store(output, "urls of the applications to monitor");
		}
	}

	private static void readCollectorApplications() throws IOException {
		// le fichier applications.properties contient les noms et les urls des applications à monitorer
		// par ex.: recette=http://recette1:8080/myapp
		// ou recette2=http://recette2:8080/myapp
		// ou production=http://prod1:8080/myapp,http://prod2:8080/myapp
		// ou aggregation=recette,recette2
		// Dans une instance de Properties, les propriétés ne sont pas ordonnées,
		// mais elles seront ordonnées lorsqu'elles seront mises dans cette TreeMap
		final Map<String, List<URL>> applications = new TreeMap<>();
		final Map<String, List<String>> aggregationApplications = new TreeMap<>();
		final File file = getCollectorApplicationsFile();
		if (file.exists()) {
			final Properties properties = new Properties();
			try (FileInputStream input = new FileInputStream(file)) {
				properties.load(input);
			}
			@SuppressWarnings("unchecked")
			final List<String> propertyNames = (List<String>) Collections
					.list(properties.propertyNames());
			for (final String property : propertyNames) {
				final String value = String.valueOf(properties.get(property));
				if (value.startsWith("http")) {
					applications.put(property, parseUrls(value));
				} else {
					aggregationApplications.put(property,
							new ArrayList<>(List.of(value.split(","))));
				}
			}
		}
		urlsByApplications = applications;
		applicationsByAggregationApplications = aggregationApplications;

		synchronizeAggregationApplications();
	}

	private static void synchronizeAggregationApplications() {
		for (final Iterator<List<String>> it1 = applicationsByAggregationApplications.values()
				.iterator(); it1.hasNext();) {
			final List<String> aggregatedApplications = it1.next();
			// on supprime les applications aggrégées inconnues
			aggregatedApplications.removeIf(aggregatedApplication -> !urlsByApplications
					.containsKey(aggregatedApplication)
					&& !applicationsByAggregationApplications.containsKey(aggregatedApplication));
			if (aggregatedApplications.isEmpty()) {
				// application d'aggrégation vide, on la supprime
				it1.remove();
			}
		}
	}

	public static File getCollectorApplicationsFile() {
		return new File(getStorageDirectory(""), COLLECTOR_APPLICATIONS_FILENAME);
	}

	public static List<URL> parseUrls(String value) throws MalformedURLException {
		// pour un cluster, le paramètre vaut "url1,url2"
		final TransportFormat transportFormat;
		if (Parameter.TRANSPORT_FORMAT.getValue() == null) {
			transportFormat = TransportFormat.SERIALIZED;
		} else {
			transportFormat = TransportFormat
					.valueOfIgnoreCase(Parameter.TRANSPORT_FORMAT.getValue());
		}
		final String suffix = getMonitoringPath() + "?collector=stop&format="
				+ transportFormat.getCode();

		final String[] urlsArray = value.split(",");
		final List<URL> urls = new ArrayList<>(urlsArray.length);
		for (final String s : urlsArray) {
			String s2 = s.trim();
			final String queryString;
			if (s2.indexOf('?') != -1) {
				// queryString is not url encoded here to create URL. URL encode it before if needed.
				queryString = s2.substring(s2.indexOf('?') + 1);
				s2 = s2.substring(0, s2.indexOf('?'));
			} else {
				queryString = null;
			}
			while (s2.endsWith("/")) {
				s2 = s2.substring(0, s2.length() - 1);
			}
			final URL url = new URL(s2 + suffix + (queryString == null ? "" : "&" + queryString));
			urls.add(url);
		}
		return urls;
	}

	public static String getMonitoringPath() {
		final String parameterValue = Parameter.MONITORING_PATH.getValue();
		if (parameterValue == null) {
			return DEFAULT_MONITORING_PATH;
		}
		return parameterValue;
	}

	/**
	 * @return nom réseau de la machine
	 */
	public static String getHostName() {
		if (dnsLookupsDisabled) {
			return "localhost";
		}

		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (final UnknownHostException ex) {
			return "unknown";
		}
	}

	/**
	 * @return adresse ip de la machine
	 */
	public static String getHostAddress() {
		if (dnsLookupsDisabled) {
			return "127.0.0.1"; // NOPMD
		}

		try {
			return InetAddress.getLocalHost().getHostAddress();
		} catch (final UnknownHostException ex) {
			return "unknown";
		}
	}

	/**
	 * @param fileName Nom du fichier de resource.
	 * @return Chemin complet d'une resource.
	 */
	public static String getResourcePath(String fileName) {
		return "/net/bull/javamelody/resource/" + fileName;
	}

	/**
	 * @return Résolution en secondes des courbes et période d'appels par le serveur de collecte le cas échéant.
	 */
	public static int getResolutionSeconds() {
		final String param = Parameter.RESOLUTION_SECONDS.getValue();
		if (param != null) {
			// lance une NumberFormatException si ce n'est pas un nombre
			final int result = Integer.parseInt(param);
			if (result <= 0) {
				throw new IllegalStateException(
						"The parameter resolution-seconds should be > 0 (between 60 and 600 recommended)");
			}
			return result;
		}
		return DEFAULT_RESOLUTION_SECONDS;
	}

	/**
	 * @param application Nom de l'application
	 * @return Répertoire de stockage des compteurs et des données pour les courbes.
	 */
	public static File getStorageDirectory(String application) {
		final String param = Parameter.STORAGE_DIRECTORY.getValue();
		final String dir;
		if (param == null) {
			dir = DEFAULT_DIRECTORY;
		} else {
			dir = param;
		}
		// Si le nom du répertoire commence par '/' (ou "drive specifier" sur Windows),
		// on considère que c'est un chemin absolu,
		// sinon on considère que c'est un chemin relatif par rapport au répertoire temporaire
		// ('temp' dans TOMCAT_HOME pour tomcat).
		final String directory;
		if (!dir.isEmpty() && new File(dir).isAbsolute()) {
			directory = dir;
		} else {
			directory = TEMPORARY_DIRECTORY.getPath() + '/' + dir;
		}
		if (servletContext != null) {
			return new File(directory + '/' + application);
		}
		return new File(directory);
	}

	/**
	 * Booléen selon que le paramètre no-database vaut true.
	 * @return boolean
	 */
	public static boolean isNoDatabase() {
		return Parameter.NO_DATABASE.getValueAsBoolean();
	}

	/**
	 * Booléen selon que le paramètre system-actions-enabled vaut true.
	 * @return boolean
	 */
	public static boolean isSystemActionsEnabled() {
		final String parameter = Parameter.SYSTEM_ACTIONS_ENABLED.getValue();
		return parameter == null || Boolean.parseBoolean(parameter);
	}

	public static boolean isPdfEnabled() {
		return PDF_ENABLED;
	}

	private static boolean computePdfEnabled() {
		try {
			Class.forName("com.lowagie.text.Document");
			return true;
		} catch (final ClassNotFoundException e) {
			return false;
		}
	}

	/**
	 * Retourne false si le paramètre displayed-counters n'a pas été défini
	 * ou si il contient le compteur dont le nom est paramètre,
	 * et retourne true sinon (c'est-à-dire si le paramètre displayed-counters est défini
	 * et si il ne contient pas le compteur dont le nom est paramètre).
	 * @param counterName Nom du compteur
	 * @return boolean
	 */
	public static boolean isCounterHidden(String counterName) {
		final String displayedCounters = Parameter.DISPLAYED_COUNTERS.getValue();
		if (displayedCounters == null) {
			return false;
		}
		for (final String displayedCounter : displayedCounters.split(",")) {
			final String displayedCounterName = displayedCounter.trim();
			if (counterName.equalsIgnoreCase(displayedCounterName)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * @return Nom de l'application courante et nom du sous-répertoire de stockage dans une application monitorée.
	 */
	public static String getCurrentApplication() {
		// use explicitly configured application name (if configured)
		final String applicationName = Parameter.APPLICATION_NAME.getValue();
		if (applicationName != null) {
			return applicationName;
		}
		if (servletContext != null) {
			// Le nom de l'application et donc le stockage des fichiers est dans le sous-répertoire
			// ayant pour nom le contexte de la webapp et le nom du serveur
			// pour pouvoir monitorer plusieurs webapps sur le même serveur et
			// pour pouvoir stocker sur un répertoire partagé entre plusieurs serveurs
			return servletContext.getContextPath() + '_' + getHostName();
		}
		return null;
	}

	private static String getJavaMelodyVersion() {
		final InputStream inputStream = Parameters.class
				.getResourceAsStream("/JAVAMELODY-VERSION.properties");
		if (inputStream == null) {
			return null;
		}

		final Properties properties = new Properties();
		try {
			try (inputStream) {
				properties.load(inputStream);
				return properties.getProperty("version");
			}
		} catch (final IOException e) {
			return e.toString();
		}
	}

	/**
	 * Recherche la valeur d'un paramètre qui peut être défini par ordre de priorité croissant : <br/>
	 * - dans les paramètres d'initialisation du filtre (fichier web.xml dans la webapp) <br/>
	 * - dans les paramètres du contexte de la webapp avec le préfixe "javamelody." (fichier xml de contexte dans Tomcat) <br/>
	 * - dans les variables d'environnement du système d'exploitation avec le préfixe "javamelody." <br/>
	 * - dans les propriétés systèmes avec le préfixe "javamelody." (commande de lancement java).
	 * @param parameter Enum du paramètre
	 * @return valeur du paramètre ou null si pas de paramètre défini
	 */
	public static String getParameterValue(Parameter parameter) {
		assert parameter != null;
		final String name = parameter.getCode();
		return getParameterValueByName(name);
	}

	public static String getParameterValueByName(String parameterName) {
		assert parameterName != null;
		final String globalName = PARAMETER_SYSTEM_PREFIX + parameterName;
		String result = System.getProperty(globalName);
		if (result != null) {
			return result;
		}
		if (servletContext != null) {
			result = servletContext.getInitParameter(globalName);
			if (result != null) {
				return result;
			}
			// issue 463: in a ServletContextListener, it's also possible to call servletContext.setAttribute("javamelody.log", "true"); for example
			final Object attribute = servletContext.getAttribute(globalName);
			if (attribute instanceof String) {
				return (String) attribute;
			}
		}
		if (filterConfig != null) {
			return filterConfig.getInitParameter(parameterName);
		}
		return null;
	}
}
