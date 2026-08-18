from endstone.command import Command, CommandSender
from endstone.plugin import Plugin


class OniBridgeCommandFixture(Plugin):
    api_version = "0.11"
    commands = {
        "fixture": {
            "description": "OniBridge command transport fixture",
            "usages": [
                "/fixture simple",
                "/fixture typed <count: int> <ratio: float> <enabled: bool>",
                "/fixture text <message: message>",
                "/fixture target <players: target>",
            ],
            "aliases": ["fx"],
            "permissions": ["onibridge.fixture.command"],
        }
    }
    permissions = {
        "onibridge.fixture.command": {
            "description": "Execute the Python command transport fixture",
            "default": "op",
        }
    }

    def on_command(self, sender: CommandSender, command: Command, args: list[str]) -> bool:
        sender.send_message(f"fixture:{command.name}:{'|'.join(args)}")
        return True

