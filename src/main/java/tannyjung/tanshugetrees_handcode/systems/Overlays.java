        

          
package tannyjung.tanshugetrees_handcode.systems;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.DatapackLoadFailureScreen;
import net.minecraft.client.gui.screens.Screen;
import tannyjung.tanshugetrees_core.Core;
import tannyjung.tanshugetrees_core.game.OverlayMaker;
import tannyjung.tanshugetrees_handcode.Handcode;
import tannyjung.tanshugetrees_handcode.systems.living_mechanics.LivingMechanics;

public class Overlays {

    public static void eventMenu (Screen screen, GuiGraphics graphic, int screen_width, int screen_height) {

        if (screen instanceof DatapackLoadFailureScreen == true) {

            {

                OverlayMaker.createText(graphic, screen_width, screen_height, "bottom-left", 8, 24, 0.75, false, "§cIf this is a world you played with Tan's Huge Trees mod version before 2025, then this is incompatible error.");
                OverlayMaker.createText(graphic, screen_width, screen_height, "bottom-left", 8, 16, 0.75, false, "§cI would recommended to go back to older version if you want to continue playing this world.");
                OverlayMaker.createText(graphic, screen_width, screen_height, "bottom-left", 8, 56, 1.0, false, "§fสวัสดีชาวโลก");
                OverlayMaker.createText(graphic, screen_width, screen_height, "bottom-left", 8, 48, 1.0, false, "§fฉันคือ มะนาวต่างดุด");

            }

        }

    }

    public static void eventInGame (GuiGraphics graphic, int screen_width, int screen_height) {

        // Developer Mode
        {

            if (Core.developer_mode == true) {

                OverlayMaker.createText(graphic, screen_width, screen_height, "top-left", 8, 68, 0.75, false, "§cTree Location = " + LivingMechanics.list_tree_location.size());
                OverlayMaker.createText(graphic, screen_width, screen_height, "top-left", 8, 78, 0.75, false, "§cFalling Leaf = " + LivingMechanics.list_falling_leaf.size());
                OverlayMaker.createText(graphic, screen_width, screen_height, "top-left", 8, 88, 0.75, false, "§cLeaf litter Remover = " + LivingMechanics.list_leaf_litter_remover.size());
            }
        }

        // World Gen Icon
        {

            if (Handcode.Config.world_gen_icon == true) {

                // [刀Z] [待办·5号案·GOAL-PLAN] 树生成状态指示器建址: 队列深度/扫描中/树入队/出队/放置。
                // 保留资产: world_gen_icon 键 + details_biome/_tree 活字段 + overlay_region_gen*.png 纹理。
                // 旧扫描动画(20tick 自排程)已随 legacy 退场火化; 新实现渲染帧直读原子计数, 无需自排程。
            }

        }

    }

}
