package anvisa.inflabnet.fiscalizacao.ui.consulta

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProviders
import androidx.navigation.fragment.findNavController
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import anvisa.inflabnet.fiscalizacao.R
import anvisa.inflabnet.fiscalizacao.database.service.AppDatabase
import anvisa.inflabnet.fiscalizacao.database.service.AppDatabaseService
import anvisa.inflabnet.fiscalizacao.database.tabelas.Avaliacoes
import anvisa.inflabnet.fiscalizacao.login.MainActivity
import anvisa.inflabnet.fiscalizacao.ui.consulta.viewmodel.ConsultaViewModel
import com.android.billingclient.api.*
import com.facebook.login.LoginManager
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import kotlinx.android.synthetic.main.fragment_cadastro.*
import kotlinx.android.synthetic.main.fragment_consulta.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.properties.Delegates

class ConsultaFragment : Fragment(),
    BillingClientStateListener,
    SkuDetailsResponseListener,
    PurchasesUpdatedListener,
    ConsumeResponseListener {

    private lateinit var consultaViewModel: ConsultaViewModel
    private lateinit var appDatabase : AppDatabase
    private var mAuth: FirebaseAuth? = null

    //compras
    private lateinit var clienteInApp: BillingClient
    private var mapSku = HashMap<String,SkuDetails>()
    private var currentSku = "android.test.purchased"
    val keyGenParameterSpec = MasterKeys.AES256_GCM_SPEC
    val masterKeyAlias = MasterKeys.getOrCreate(keyGenParameterSpec)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        val contextFrag = requireActivity().applicationContext
        appDatabase = AppDatabaseService.getInstance(contextFrag)

        consultaViewModel =
            ViewModelProviders.of(this).get(ConsultaViewModel::class.java)

        return inflater.inflate(R.layout.fragment_consulta, container, false)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mAuth = FirebaseAuth.getInstance()
        txtLogin.text = mAuth!!.currentUser?.email

        val sharedCripto = EncryptedSharedPreferences
            .create(
                "simpleFile",
                masterKeyAlias,
                requireContext(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        val resultado = sharedCripto.getString("produto","consumido")
        Log.i("Compra Cons", resultado.toString())
        if(resultado.toString() == "comprado"){
            logoutBtnRemoveAnuncio.visibility = View.GONE
            adView.visibility = View.GONE
            lConsumoAnuncio.visibility = View.VISIBLE

        }else{
            logoutBtnRemoveAnuncio.visibility = View.VISIBLE
            adView.visibility = View.VISIBLE
            lConsumoAnuncio.visibility = View.GONE
        }

        clienteInApp = BillingClient.newBuilder(requireActivity())
            .enablePendingPurchases()
            .setListener(this)
            .build()
        clienteInApp.startConnection(this)

        logoutBtnRemoveAnuncio.setOnClickListener {
            processGoogleInApp()
        }

        lConsumoAnuncio.setOnClickListener {
                consumeGoogleInApp()
         }

        MobileAds.initialize(requireActivity()) {}

        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)

        btnAutuacoesLista.setOnClickListener {
            findNavController().navigate(R.id.action_navigation_consulta_to_listaAutuacoesFragment)
        }

        //ID do avaliador
        val idFiscalLogado = GetIdAvaliador().execute(101).get()

        //pegar todos os estabelecimentos do avaliador logado
        val listaTodos = GetEstabelecimentos().execute(idFiscalLogado).get()

        consultaViewModel.montaAlertDialog(listaTodos,rvAvaliacoes,requireActivity(),requireContext(),context?.applicationContext,consultaLayout)

        logoutBtnConsulta.setOnClickListener {
            LoginManager.getInstance().logOut()
            mAuth = FirebaseAuth.getInstance()
            mAuth!!.signOut()

            val novoIntt = Intent(requireContext(), MainActivity::class.java)
            novoIntt.flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(novoIntt)

        }

    }

    override fun onDestroy() {
        clienteInApp.endConnection()
        super.onDestroy()
    }

    @SuppressLint("StaticFieldLeak")
    inner class GetEstabelecimentos: AsyncTask<String,Unit,List<Avaliacoes>>(){
        override fun doInBackground(vararg params: String?): List<Avaliacoes> {
            return appDatabase.avaliacoesDAO().getAll(params[0]!!)
        }

    }

    @SuppressLint("StaticFieldLeak")
    inner class GetIdAvaliador: AsyncTask<Int,Unit,String>() {
        override fun doInBackground(vararg params: Int?): String {
            return appDatabase.fiscalDAO().getIdAvaliadorAtual(params[0]!!)
        }
    }

    override fun onBillingServiceDisconnected() {
        Log.d("COMPRA>>","Serviço InApp desconectado")
    }

    override fun onBillingSetupFinished(p0: BillingResult) {
        if(p0.responseCode ==
            BillingClient.BillingResponseCode.OK){
            Log.d("COMPRA>>","Serviço InApp conectado")
            val skuList = arrayListOf(currentSku)
            val params = SkuDetailsParams.newBuilder()
            params.setSkusList(skuList).setType(
                BillingClient.SkuType.INAPP)
            clienteInApp.querySkuDetailsAsync(params.build(), this)
        }
    }

    override fun onSkuDetailsResponse(p0: BillingResult, p1: MutableList<SkuDetails>?) {
        if(p0.responseCode ==
            BillingClient.BillingResponseCode.OK){
            mapSku.clear()
            p1?.forEach{
                    t ->
                val preco = t.price
                val descricao = t.description
                mapSku[t.sku] = t
                Log.d("COMPRA>>",
                    "Produto Disponivel ($preco): $descricao")
            }
        }

    }

    override fun onPurchasesUpdated(p0: BillingResult, p1: MutableList<Purchase>?) {
        if (p0.responseCode ==
            BillingClient.BillingResponseCode.OK &&
            p1 != null) {
            for (purchase in p1) {
                GlobalScope.launch(Dispatchers.IO){
                    handlePurchase(purchase)
                }
            }
        } else if (p0.responseCode ==
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            Log.d("COMPRA>>","Produto já foi comprado")

        } else if (p0.responseCode ==
            BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.d("COMPRA>>","Usuário cancelou a compra")
        } else {
            Log.d("COMPRA>>",
                "Código de erro desconhecido: ${p0.responseCode}")
        }
    }

    override fun onConsumeResponse(p0: BillingResult, p1: String) {
        if(p0.responseCode==
            BillingClient.BillingResponseCode.OK) {
            Log.d("COMPRA>>", "Produto Consumido")
         }
    }

    suspend fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED)
        {
            
            // Aqui acessaria a base e concederia o produto ao usuário
            Log.d("COMPRA>>","Produto obtido com sucesso")
            val sharedCripto = EncryptedSharedPreferences
                .create(
                    "simpleFile",
                    masterKeyAlias,
                    requireContext(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            val editor = sharedCripto.edit()
            editor.putString("produto", "comprado")
            editor.apply()

            //findNavController().navigate(R.id.navigation_cadastro)

            LoginManager.getInstance().logOut()
            mAuth = FirebaseAuth.getInstance()
            mAuth!!.signOut()

            val novoIntt = Intent(requireContext(), MainActivity::class.java)
            novoIntt.flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(novoIntt)


            // Acknowledge -> Obrigatório para confirmação ao Google
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams
                    .newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                val ackPurchaseResult = withContext(Dispatchers.IO) {
                    clienteInApp.acknowledgePurchase(
                        acknowledgePurchaseParams.build())
                }
            }
        }
    }

    fun processGoogleInApp(){
        // O valor é definido no Google Dev Console
        // Toda a transação é controlada no ambiente do Google
        val skuDetails = mapSku[currentSku]
        val purchaseParams = skuDetails?.let {
            BillingFlowParams.newBuilder()
                .setSkuDetails(it).build()
        }
        if (purchaseParams != null) {
            clienteInApp.launchBillingFlow(requireActivity(),purchaseParams)
        }
    }

    fun consumeGoogleInApp() {
        val sharedCripto = EncryptedSharedPreferences
            .create(
                "simpleFile",
                masterKeyAlias,
                requireContext(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        val editor = sharedCripto.edit()
        editor.putString("produto", "consumido")
        Log.i("Compra Consumo","passei")
        editor.apply()
        Toast.makeText(requireContext(),"Produto consumido com sucesso!",Toast.LENGTH_LONG).show()

        LoginManager.getInstance().logOut()
        mAuth = FirebaseAuth.getInstance()
        mAuth!!.signOut()

        val compras = clienteInApp.queryPurchases(
            BillingClient.SkuType.INAPP)
        if( compras.purchasesList!!.size > 0 )
        {
            val purchase: Purchase = compras.purchasesList!![0]
            val params = ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                 //.setDeveloperPayload(purchase.developerPayload)
                .build()
            clienteInApp.consumeAsync(params,this)
        }
    }

    private fun showSnackbar(msg: String?) {
        val snack = Snackbar.make(consultaLayout,msg.toString(), Snackbar.LENGTH_LONG)
        snack.show()
    }
}
