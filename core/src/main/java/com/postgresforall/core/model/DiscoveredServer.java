package com.postgresforall.core.model;

/**
 * A PostgreSQL server the Object Explorer can show: where it is, what it's
 * called, and any hints for logging in. Identity is {@link #key()}.
 *
 * @param label             friendly label: config name, container name or service name
 * @param sources           {@link ServerSource} flags
 * @param dockerContainer   Docker container publishing this port, when Docker knows it
 * @param service           native service running this server (Windows service name / launchd label)
 * @param serviceRunning    false when the backing service is known to be stopped
 */
public record DiscoveredServer(
        String host,
        int port,
        String label,
        int sources,
        String dockerContainer,
        String service,
        Boolean serviceRunning,
        String suggestedUsername,
        String suggestedPassword,
        String suggestedDatabase) {

    public static DiscoveredServer of(String host, int port, int sources) {
        return new DiscoveredServer(host, port, null, sources, null, null, null, null, null, null);
    }

    public String key() {
        return ServerKey.of(host, port);
    }

    public String address() {
        return host + ":" + port;
    }

    /** "localhost:5444" plus the label when there is one. */
    public String displayName() {
        return label == null ? address() : address() + "  " + label;
    }

    /** Combines what two discovery sources learned about the same server. */
    public DiscoveredServer mergeWith(DiscoveredServer other) {
        return new DiscoveredServer(
                host, port,
                label != null ? label : other.label,
                sources | other.sources,
                dockerContainer != null ? dockerContainer : other.dockerContainer,
                service != null ? service : other.service,
                serviceRunning != null ? serviceRunning : other.serviceRunning,
                suggestedUsername != null ? suggestedUsername : other.suggestedUsername,
                suggestedPassword != null ? suggestedPassword : other.suggestedPassword,
                suggestedDatabase != null ? suggestedDatabase : other.suggestedDatabase);
    }

    public DiscoveredServer withLabel(String l) {
        return new DiscoveredServer(host, port, l, sources, dockerContainer, service, serviceRunning,
                suggestedUsername, suggestedPassword, suggestedDatabase);
    }

    public DiscoveredServer withDockerContainer(String c) {
        return new DiscoveredServer(host, port, label, sources, c, service, serviceRunning,
                suggestedUsername, suggestedPassword, suggestedDatabase);
    }

    public DiscoveredServer withService(String s, Boolean running) {
        return new DiscoveredServer(host, port, label, sources, dockerContainer, s, running,
                suggestedUsername, suggestedPassword, suggestedDatabase);
    }

    public DiscoveredServer withSuggestions(String user, String password, String db) {
        return new DiscoveredServer(host, port, label, sources, dockerContainer, service, serviceRunning,
                user, password, db);
    }
}
