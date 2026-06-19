package aiplay.config.global;

public record Ut99ServerConfig(
    String utNeuralnetServer,
    int uwebListenPort,
    int port,
    int minPlayers,
    String installRoot
) {}
