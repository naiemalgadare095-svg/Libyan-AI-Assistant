package com.naeem.assistant

import org.junit.Assert.*
import org.junit.Test

class CommandParserTest {
    @Test fun mixedLanguagePlanRetainsOrder() {
        val p=CommandParser.plan("افتح يوتيوب ثم open WhatsApp ثم call Sara")
        assertEquals("",p.error)
        assertEquals(listOf(Action.YOUTUBE,Action.WHATSAPP,Action.DIAL),p.steps.map{it.action})
        assertEquals("sara",p.steps[2].recipient)
    }
    @Test fun messageIsOpaque() {
        val p=CommandParser.plan("ابعت SMS إلى أحمد \"ثم افتح يوتيوب وبعدين اتصل بسارة\" ثم افتح واتساب")
        assertEquals("",p.error);assertEquals(2,p.steps.size)
        assertEquals("ثم افتح يوتيوب وبعدين اتصل بسارة",p.steps[0].message)
        assertEquals("احمد",p.steps[0].recipient)
    }
    @Test fun messageCannotTurnIntoAnAlarm() {
        val p=CommandParser.parse("send SMS to Sara \"set alarm at 6:30\"")
        assertEquals(Action.SMS,p.action)
        assertEquals("set alarm at 6:30",p.message)
        assertEquals(Action.DIAL,CommandParser.parse("call alarm company").action)
    }
    @Test fun messageCannotChangeItsChannel() {
        assertEquals(Action.SMS,CommandParser.parse("ابعت SMS إلى أحمد \"افتح واتساب\"").action)
    }
    @Test fun quotedEnglishRetainsCase() {
        val c=CommandParser.parse("send WhatsApp to Sara \"I am late and then home\"")
        assertEquals(Action.WHATSAPP,c.action);assertEquals("I am late and then home",c.message)
    }
    @Test fun doNotExecutePartiallyParsedPlan() {
        val p=CommandParser.plan("افتح يوتيوب ثم احذف كل حاجة")
        assertTrue(p.steps.isEmpty());assertTrue(p.error.isNotBlank())
    }
    @Test fun unknownMentionOfAppIsNotAnOpenCommand() {
        assertEquals(Action.UNKNOWN,CommandParser.parse("لا تفتح يوتيوب").action)
        assertEquals(Action.UNKNOWN,CommandParser.parse("لا تتصل على أحمد").action)
        assertEquals(Action.UNKNOWN,CommandParser.parse("هل واتساب موجود؟").action)
    }
    @Test fun unquotedMessagesFailClosed() {
        assertEquals(Action.UNKNOWN,CommandParser.parse("ابعت SMS إلى أحمد بنتأخر").action)
        assertTrue(CommandParser.plan("ابعت SMS إلى أحمد \"نص").steps.isEmpty())
    }
    @Test fun missingRecipientAndChannelAreRejected() {
        assertEquals(Action.UNKNOWN,CommandParser.parse("ابعت SMS \"hello\"").action)
        assertEquals(Action.UNKNOWN,CommandParser.parse("ابعت إلى أحمد \"hello\"").action)
        assertEquals(Action.UNKNOWN,CommandParser.parse("اتصل").action)
    }
    @Test fun normalizeDigitsAndDiacritics() {
        assertEquals("احمد 0123456789",CommandParser.normalize("أَحْمَد ٠١٢٣٤٥٦٧٨٩"))
        assertEquals("+218912345678",CommandParser.phone("+٢١٨ (٩١) ٢٣٤-٥٦٧٨"))
        assertNull(CommandParser.phone("+218;12345"));assertNull(CommandParser.phone("12"))
        assertNull(CommandParser.phone("1234567890123456"))
    }
    @Test fun clockVariants() {
        assertEquals(18,CommandParser.parse("ديرلي منبه الساعة 6:30 بالليل").hour)
        assertEquals(0,CommandParser.parse("ديرلي منبه الساعة 12:00 الصبح").hour)
        assertEquals(12,CommandParser.parse("set alarm at 12:00 pm").hour)
        assertEquals(15,CommandParser.parse("نبهني الساعة ستة وربع").minute)
        assertEquals(6,CommandParser.parse("منبه الساعة ٦:٣٠").hour)
        assertEquals(30,CommandParser.parse("نبهني الساعة ستة ونص").minute)
    }
    @Test fun invalidTimesNeverBecomeAlarm() {
        listOf("منبه 25:30","منبه 18:90","منبه الساعة 123","منبه الساعة 0:30 pm","منبه 6:00 صباح بالليل","منبه بكرة").forEach {
            assertEquals(it,Action.UNKNOWN,CommandParser.parse(it).action)
        }
    }
    @Test fun wakePrefixAndArabicConjunctions() {
        val p=CommandParser.plan("يا مساعدي افتح يوتيوب وافتح واتساب")
        assertEquals("",p.error);assertEquals(2,p.steps.size)
        assertEquals(2,CommandParser.plan("افتح يوتيوب؛افتح واتساب").steps.size)
    }
    @Test fun punctuationAndQuotationStyles() {
        assertEquals("بنتأخر",CommandParser.parse("ابعت SMS إلى أحمد «بنتأخر»").message)
        assertEquals("بنتأخر",CommandParser.parse("ابعت SMS إلى أحمد “بنتأخر”").message)
        assertTrue(CommandParser.plan(" ").steps.isEmpty())
    }
    @Test fun boundedPlan() {
        assertTrue(CommandParser.plan(List(7){"افتح يوتيوب"}.joinToString(" ثم ")).steps.isEmpty())
    }
    @Test fun requiredSensitiveActions() {
        assertTrue(CommandParser.parse("call Sara").sensitive)
        assertTrue(CommandParser.parse("ابعت SMS إلى أحمد \"test\"").sensitive)
        assertTrue(CommandParser.parse("send WhatsApp to Sara \"test\"").sensitive)
        assertFalse(CommandParser.parse("افتح واتساب").sensitive)
    }
    @Test fun recipientContainingThenIsNotSplitInsideAWord() {
        assertEquals(1,CommandParser.plan("call Nathanthen Smith").steps.size)
    }
    @Test fun confirmationExpiresAndCannotBeReplayed() {
        val g=ConfirmationGate();g.arm("a",100)
        assertFalse(g.accept("a",30_100,true,true))
        g.arm("b",200);assertTrue(g.accept("b",201,true,true));assertFalse(g.accept("b",202,true,true))
    }
    @Test fun confirmationCannotMigrateToAnotherAction() {
        val g=ConfirmationGate();g.arm("a",100)
        assertFalse(g.accept("b",101,true,true));assertFalse(g.accept("a",102,true,true))
    }
    @Test fun lockedOrBackgroundOrCancelledNeverAccepts() {
        val g=ConfirmationGate()
        g.arm("a",0);assertFalse(g.accept("a",1,false,true))
        g.arm("a",0);assertFalse(g.accept("a",1,true,false))
        g.arm("a",0);g.invalidate();assertFalse(g.accept("a",1,true,true))
    }
}
