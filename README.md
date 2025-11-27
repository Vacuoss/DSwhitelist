Plugin to Discord-driven whitelist manager for Spigot/Paper Minecraft servers

DSwhitelist connects your server to Discord and allows administrators to add players to the whitelist directly through slash commands, buttons, and modal dialogs

Features:
Commands in Discrod:

Use /panel to send an embed with the Add Account button 

/wl add <player> - command to add player 

Works on nearly all Spigot-based servers:
◽ Spigot

◽ Paper

◽ Purpur

◽ Pufferfish

◽ And any other Spigot-compatible fork

HOW TO USE:

Place DSwhitelist.jar into your plugins/ folder

Start the server to generate config.yml

Open config.yml and insert your Discord bot token:

<img width="315" height="48" alt="image" src="https://github.com/user-attachments/assets/d5017a50-8af3-4d15-8e7e-736b5efa776f" />

Technical Overview:
◽ Fully asynchronous network operations

◽ Uses JDA for bot events, interactions, commands, modals, and buttons

◽ Configurable global/guild command registration

◽ ExecutorService for parallel tasks

◽ Safe shutdown on plugin disable


