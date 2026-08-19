#include <endstone/endstone.hpp>

class OniBridgeCommandFixture final : public endstone::Plugin {
  public:
    bool onCommand(endstone::CommandSender& sender,
                   const endstone::Command& command,
                   const std::vector<std::string>& args) override {
        std::string result = "fixture:" + command.getName();
        for (const auto& argument : args)
            result += "|" + argument;
        sender.sendMessage(result);
        return true;
    }
};

ENDSTONE_PLUGIN("onibridge_command_fixture", "1.0.0", OniBridgeCommandFixture) {
    command("nativefixture")
        .description("OniBridge native command transport fixture")
        .aliases("nfx")
        .usages("/nativefixture simple",
                "/nativefixture typed <count: int> <ratio: float> <enabled: bool>",
                "/nativefixture text <message: message>")
        .permissions("onibridge.fixture.native");
    permission("onibridge.fixture.native")
        .description("Execute the native command transport fixture")
        .default_(endstone::PermissionDefault::Operator);
}
