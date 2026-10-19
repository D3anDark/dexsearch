package demo.dex

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.Locale

/** Saved teams, each with its game and six slots, and a button that starts a new one in the selected game. */
class TeamsActivity : Activity() {
    private lateinit var box: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Ui.dex(this)
        val root = Ui.vertical(this)
        root.setPadding(0, 0, 0, Ui.dp(this, 16))
        root.addView(Ui.title(this, L.t("Teams")))
        val help = Ui.muted(this, L.t("Up to six Pokémon per team, with moves, items and stats for one game. Saved on this device."), 14)
        help.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 12))
        root.addView(help)
        box = Ui.vertical(this)
        root.addView(box)
        val scroll = ScrollView(this)
        scroll.addView(root)
        Ui.show(this, scroll)
    }

    /**
     * Rebuilt on every return, since the team screens change names and members. ponytail: every slot sprite stays
     * decoded (64 KB each, 6 per team); a recycling ListView if people keep dozens of teams.
     */
    override fun onResume() {
        super.onResume()
        val dex = Ui.dex(this)
        val teams = load(this)
        box.removeAllViews()
        for ((index, t) in teams.withIndex()) {
            val card = Ui.vertical(this)
            card.background = Ui.pressable(this, Ui.rounded(this, Ui.SURFACE, 18), 18)
            card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14))
            card.addView(Ui.text(this, t.name, 17, true))
            card.addView(Ui.muted(this, dex.game(t.game).name + "  ·  " + L.f("%d of %d", t.members.size, Team.SIZE), 13))
            val slots = LinearLayout(this)
            slots.setPadding(0, Ui.dp(this, 10), 0, 0)
            for (k in 0 until Team.SIZE) {
                val img = ImageView(this)
                val circle = GradientDrawable()
                circle.shape = GradientDrawable.OVAL
                circle.setColor(Ui.FILL)
                img.background = circle
                if (k < t.members.size) img.setImageDrawable(Ui.sprite(this, t.members[k].num, 2))
                val lp = LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f)
                lp.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0)
                slots.addView(img, lp)
            }
            card.addView(slots)
            card.setOnClickListener { TeamActivity.open(this, index) }
            box.addView(card, Ui.wide(this, 0, 10))
        }
        val add = Ui.text(this, L.f("New team for %s", Ui.game(this).name), 15, true)
        add.gravity = Gravity.CENTER
        add.setTextColor(Ui.WHITE)
        add.background = Ui.pressable(this, Ui.rounded(this, Ui.ACCENT, 999), 999)
        add.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14))
        add.setOnClickListener {
            teams.add(Team(L.f("Team %d", teams.size + 1), Ui.game(this).id))
            save(this, teams)
            TeamActivity.open(this, teams.size - 1)
        }
        box.addView(add, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 16, 6, 16, 0))
    }

    companion object {
        /** All teams live in one preference string, a line per team; a full set of 20 teams is about 3 KB. */
        fun load(c: Context): MutableList<Team> {
            val teams = Team.parseAll(Ui.prefs(c).getString(Ui.key(c, "teams"), "")!!)
            val dex = Ui.dex(c)
            for (t in teams) for (m in t.members) m.num = dex.known(m.num)
            return teams
        }

        fun save(c: Context, teams: List<Team>) {
            Ui.prefs(c).edit().putString(Ui.key(c, "teams"), Team.formatAll(teams)).apply()
        }

        /** New team member: first regular ability, level 50 (the usual battle level). */
        fun member(m: Dex.Mon) = Team.Member(m.num, if (m.abilities.isNotEmpty()) m.abilities[0] else m.hidden, 50)

        /**
         * Picks a Pokémon of `game` in tabs: `first` (title and rows) when given, then the caught ones (Box), then all.
         * A row whose ref is a team member passes that member, any other a new one ([member]).
         */
        fun pick(a: Activity, dex: Dex, game: Dex.Game, heading: String, first: Pair<String, List<Ui.Item>>?, picked: (Team.Member) -> Unit) {
            val caught = Ui.caught(a)
            val all = game.dex.map { monItem(dex, game, it.mon) }
            val tabs = listOfNotNull(first?.first, L.t("Box"), L.t("All"))
            val lists = listOfNotNull(first?.second, all.filter { (it.ref as Dex.Mon).num.toString() in caught }, all)
            Ui.pickSearch(a, heading, tabs.toTypedArray(), lists) { ref -> picked(ref as? Team.Member ?: member(ref as Dex.Mon)) }
        }

        fun monItem(dex: Dex, game: Dex.Game, m: Dex.Mon) =
            Ui.Item.mon(m, dex.types(game, m), game.byNum[m.num]?.let { "#%03d".format(Locale.ROOT, it.number) } ?: "", "")

        /** Every saved team's members under the team's name, for [pick]'s first tab. */
        fun members(c: Context, dex: Dex, game: Dex.Game): List<Ui.Item> = load(c).flatMap { t ->
            listOf(Ui.Item.header(t.name)) + t.members.map { x ->
                val m = dex.mon(x.num)
                Ui.Item(m.num, L.f("Lv %d", x.level), m.name, "", dex.types(game, m), x)
            }
        }
    }
}
