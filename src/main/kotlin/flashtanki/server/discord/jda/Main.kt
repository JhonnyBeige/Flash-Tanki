package flashtanki.server.discord.jda

import flashtanki.server.discord.jda.commands.Default.DiscordLinkCommand
import flashtanki.server.discord.jda.commands.Default.HelpCommand
import flashtanki.server.discord.jda.commands.Default.OnlineCommand
import flashtanki.server.discord.jda.commands.Default.PingCommand
import flashtanki.server.discord.jda.commands.Moderator.ClearChatCommand
import flashtanki.server.discord.jda.commands.Owner.HelpCommands
import flashtanki.server.discord.jda.commands.Owner.InviteServiceCommand
import flashtanki.server.discord.jda.commands.Owner.StopCommand
import mu.KotlinLogging
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.events.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

private val logger = KotlinLogging.logger { }

@Suppress("FunctionName")
fun JDA(token: String) {

    val jda = JDABuilder.createDefault(token)
        .addEventListeners(MyEventListener())
        .addEventListeners(PingCommand())
        .addEventListeners(DiscordLinkCommand())
        .addEventListeners(StopCommand())
        .addEventListeners(OnlineCommand())
        .addEventListeners(InviteServiceCommand())
        .addEventListeners(HelpCommand())
        .addEventListeners(HelpCommands())
        .addEventListeners(ClearChatCommand())
        .setActivity(Activity.watching("FT???"))
/       .build()

    jda.awaitReady()
}

class MyEventListener : ListenerAdapter() {
    override fun onReady(event: ReadyEvent) {
        logger.info { "Bot started!"}
    }
 }
