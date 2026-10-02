/* Android extension of the loaded map UI. Included by ui_widget.c after
   its pointer helpers so the existing widget styles and hit targets are available. */
#ifdef HALO_ANDROID
static struct halo_porting_menu ui_porting_menu;
static rectangle2d ui_porting_bounds[HALO_PORTING_ROWS];
static int ui_porting_actions[HALO_PORTING_ROWS];
static int ui_porting_count, ui_porting_revision, ui_porting_context;

static int ui_porting_get_context(void)
{
    struct widget_instance *menu = ui_mouse_menu();
    char const *name;
    name = menu ? tag_get_name(menu->definition_tag_index) : NULL;
    if (main_menu_screen_is_active() || (name && !strcmp(name, "ui\\shell\\main_menu\\main_menu"))) return 1;
    if (name && strstr(name, "\\pause_game\\")) return 2;
    return 0;
}

static struct widget_instance *ui_porting_find_text(struct widget_instance *widget)
{
    struct widget_instance *child, *found;
    if (widget->type == _ui_widget_type_text_box &&
        ui_widget_definition_get(widget->definition_tag_index)->text_font.index != NONE)
        return widget;
    for (child = widget->child; child; child = child->next) {
        found = ui_porting_find_text(child);
        if (found) return found;
    }
    return NULL;
}

static struct widget_instance *ui_porting_find_style(struct widget_instance *widget)
{
    struct widget_instance *child, *found;
    if (ui_mouse_widget_is_item(widget)) {
        found = ui_porting_find_text(widget);
        if (found) return found;
    }
    for (child = widget->child; child; child = child->next) {
        found = ui_porting_find_style(child);
        if (found) return found;
    }
    return NULL;
}

static void ui_porting_prepare(void)
{
    int context = ui_porting_get_context();
    int blocked = ui_mouse_menus_active() || we_are_at_the_main_menu ||
        progress_bar_is_active() || widget_globals.initialization_thread != NULL;
    host_touch_ui_context(blocked, context);
    host_porting_menu_read(&ui_porting_menu);
    if (context != ui_porting_context) ui_porting_count = 0;
    ui_porting_context = context;
}

/* Stretch the pause frame, preserving its map bitmaps. Definitions are copied
   for this draw; imported tags and widget history remain untouched. */
static boolean ui_porting_adjust_widget(struct widget_instance *widget,
    struct ui_widget_definition **definition, struct ui_widget_definition *copy, point2d *offset)
{
    char const *name, *leaf;
    int expanded = ui_porting_menu.page != 0;
    if (!ui_porting_context || !widget->parent) return TRUE;
    if (ui_porting_menu.editing) return FALSE;
    /* Custom screens draw their own plain panel, with no inherited logo,
       objective divider or pause-frame textures underneath the text. */
    if (expanded) return FALSE;
    name = tag_get_name(widget->definition_tag_index);
    leaf = name ? strrchr(name, '\\') : NULL;
    leaf = leaf ? leaf+1 : name;
    if (!leaf) return TRUE;
    if (ui_porting_context != 2) return TRUE;
    if (!strcmp(leaf, "pause_dialog_bkd") || !strncmp(leaf, "pausebox", 8)) {
        *copy = **definition;
        copy->bounds.y1 += 84;
        *definition = copy;
    } else if (!strncmp(leaf, "button_key", 10)) {
        offset->y += 84;
    }
    return TRUE;
}

/* Consume custom rows before dispatching ordinary map widget events. */
static boolean ui_porting_pointer(struct halo_ui_pointer *pointer)
{
    int i;
    if (!ui_porting_context) return FALSE;
    if (ui_porting_menu.page && pointer->right_clicks) {
        host_porting_action(ui_porting_menu.revision, 90, 0);
        return TRUE;
    }
    if (pointer->left_clicks && ui_porting_revision == ui_porting_menu.revision) {
        for (i = 0; i < ui_porting_count; ++i) {
            rectangle2d *b = &ui_porting_bounds[i];
            if (pointer->click_x >= b->x0 && pointer->click_x < b->x1 &&
                pointer->click_y >= b->y0 && pointer->click_y < b->y1) {
                int value = PIN((pointer->click_x-b->x0)*1000 / MAX(1, b->x1-b->x0), 0, 1000);
                if (ui_porting_actions[i]) {
                    host_porting_action(ui_porting_revision, ui_porting_actions[i], value);
                    ui_play_audio_feedback_sound(_ui_audio_feedback_cursor);
                }
                return TRUE;
            }
        }
    }
    return ui_porting_menu.page != 0;
}

static void ui_porting_text(struct widget_instance *style, char const *text, rectangle2d bounds,
    rectangle2d *clip, boolean enabled)
{
    struct ui_widget_definition definition = *ui_widget_definition_get(style->definition_tag_index);
    struct widget_instance instance = *style;
    wchar_t wide[96];
    point2d offset = {0, 0};
    int percent = ui_porting_menu.page ? 160 : ui_porting_context == 2 ? 125 : 100;
    ascii_to_wide(text, wide, sizeof(wide));
    definition.bounds = bounds;
    definition.bounds.x1 = bounds.x0 + (bounds.x1-bounds.x0)*100/percent;
    definition.bounds.y1 = bounds.y0 + (bounds.y1-bounds.y0)*100/percent;
    definition.horizontal_offset = definition.vertical_offset = 0;
    definition.text_label_string_list.index = NONE;
    definition.search_and_replace_functions.count = 0;
    definition.text_box_flags = 0;
    definition.text_color.alpha = enabled ? 1.0f : 0.45f;
    definition.text_color.red = 0.85f;
    definition.text_color.green = 0.94f;
    definition.text_color.blue = 1.0f;
    if (ui_porting_menu.page) {
        long font = tag_loaded(FONT_GROUP_TAG, "ui\\large_ui");
        if (font != NONE) definition.text_font.index = font;
        definition.justification = 2;
    }
    if (strstr(text, "[ON]")) {
        definition.text_color.red = 0.25f;
        definition.text_color.green = 1.0f;
        definition.text_color.blue = 0.45f;
    }
    /* Use the same text-box draw as the existing working menu widgets.
       A synthetic label must not inherit a hidden item's parent/fade state. */
    instance.parent = NULL;
    instance.alpha_modifier = 1.0f;
    instance.visible = TRUE;
    instance.parameters.text_box.text = wide;
    draw_string_set_tab_stops(NULL, 0);
    draw_string_set_indents(0, 0);
    rasterizer_text_set_ui_scale(bounds.x0, bounds.y0, percent);
    widget_instance_render_text_box(&instance, &definition, clip, offset, FALSE);
    rasterizer_text_set_ui_scale(0, 0, 100);
}

static void ui_porting_background(void)
{
    static char const *names[] = {
        "ui\\shell\\bitmaps\\blue",
        "ui\\shell\\bitmaps\\pausebox_center",
        "ui\\shell\\bitmaps\\gradient"
    };
    rectangle2d full;
    struct bitmap_data *bitmap = NULL;
    long i, extra = (halo_screen_width()-640)/2;
    for (i = 0; i < NUMBEROF(names) && !bitmap; ++i) {
        long tag = tag_loaded(BITMAP_GROUP_TAG, names[i]);
        if (tag != NONE) bitmap = bitmap_group_get_bitmap_from_sequence(tag, 0, 0);
    }
    full.x0 = (short)-extra; full.x1 = (short)(640+extra);
    full.y0 = 0; full.y1 = 480;
    if (bitmap) draw_bitmap_in_rect(bitmap, &full, NULL, NULL, 0xffffffff, NULL, TRUE);
}

static void ui_porting_render(struct widget_instance *root, rectangle2d *clip)
{
    struct widget_instance *style;
    rectangle2d row;
    int count, i, top = 0, left = 0, right = 0, step = 44;
    static char const *sections[] = {"Overlay settings", "Hardware", "Cheats"};
    static int const sections_actions[] = {2, 3, 6};
    ui_porting_count = 0;
    if (!ui_porting_context || ui_porting_menu.editing || root != ui_mouse_menu()) return;
    style = ui_porting_find_style(root);
    if (!style) return;
    if (!ui_porting_menu.page) {
        struct widget_instance *list = NULL;
        for (i = 0; i < ui_mouse_target_count; ++i) {
            struct ui_mouse_target *target = &ui_mouse_targets[i];
            if (target->kind != _ui_mouse_target_item) continue;
            if (!list) { list = target->widget->parent; left = target->bounds.x0; right = target->bounds.x1; }
            if (target->widget->parent != list) continue;
            top = MAX(top, target->bounds.y1);
            step = MAX(24, target->bounds.y1-target->bounds.y0);
        }
        if (!list) return;
        count = ui_porting_context == 1 ? 1 : 3;
        step = MIN(step, (430-top)/count);
        if (step < 20) return;
        top += 4;
    } else {
        count = ui_porting_menu.count;
        ui_porting_background();
        left = 70; right = 570; top = 112;
        row.x0 = left; row.x1 = right; row.y0 = 60; row.y1 = 108;
        ui_porting_text(style, ui_porting_menu.title, row, clip, TRUE);
        if (count <= 2) step = 88;
    }
    ui_porting_revision = ui_porting_menu.revision;
    for (i = 0; i < count; ++i) {
        char const *text = ui_porting_menu.page ? ui_porting_menu.rows[i].text :
            ui_porting_context == 1 ? "Porting options" : sections[i];
        int action = ui_porting_menu.page ? ui_porting_menu.rows[i].action :
            ui_porting_context == 1 ? 1 : sections_actions[i];
        int value = ui_porting_menu.page ? ui_porting_menu.rows[i].value : -1;
        row.x0 = left; row.x1 = right; row.y0 = top+i*step; row.y1 = row.y0+step;
        ui_porting_bounds[i] = row; ui_porting_actions[i] = action;
        if (strstr(text, "[ON]")) draw_quad(&row, 0xaa267447);
        ui_porting_text(style, text, row, clip, action != 0);
        if (value >= 0) {
            rectangle2d bar = row, thumb;
            bar.y0 += 52; bar.y1 = bar.y0+4;
            draw_quad(&bar, 0xff58768a);
            thumb = bar;
            thumb.x0 = MIN(bar.x1-5, bar.x0+(bar.x1-bar.x0)*value/1000);
            thumb.x1 = MIN(bar.x1, thumb.x0+5);
            thumb.y0 -= 6; thumb.y1 += 6;
            draw_quad(&thumb, 0xffd7efff);
        }
        ++ui_porting_count;
    }
}
#endif
